# S3 Migration V2 Specification

## Goal

Build a Java command-line migration tool that reads S3 Inventory reports, decrypts source S3 objects through a user-provided decryptor, uploads the decrypted files to a new S3 bucket, and tracks local progress for long-running baseline, delta, and retry jobs.

The migration targets about 800 million objects, so the tool must support multi-server sharding, resumable execution, local failed logs, and repeated daily inventory runs.

## Non-Goals

- Do not implement the real decrypt logic in this project.
- Do not migrate historical S3 object versions.
- Do not preserve source object metadata, tags, ACLs, storage class, or client-side encryption metadata.
- Do not delete extra objects from the target bucket.
- Do not require DynamoDB or another shared state store.

## Project Shape

- Use Java 21.
- Use Maven.
- Use Spring Boot as a CLI application.
- Provide a runnable jar that can be deployed to 4-8 servers.
- Use configuration from `application.yml`, environment variables, and command-line overrides.

## Job Modes

The CLI must support these modes:

- `baseline`: process one baseline inventory manifest completely for the current shard.
- `delta`: process a daily inventory manifest after the current shard baseline is finished or finished with object-level failures.
- `retry`: read one or more failed JSONL files and retry those objects.

## Operational Guardrails

Configurable guardrails:

- `migration.observability.progress-log-interval`: controls intra-file progress logs while a large inventory data file is being scanned or while the reader is waiting on workers. Default: `5m`.
- `migration.observability.max-failed-log-bytes`: maximum size for each failed log before the job fails fast. Default: `10737418240` bytes, or 10 GiB.

## Inventory Input

The user supplies the S3 path to a daily S3 Inventory `manifest.json`.

Example shape:

```text
s3://inventory-bucket/path/to/source-bucket/config-id/2026-05-20T00-00Z/manifest.json
```

The tool must:

- Download the manifest JSON from the supplied S3 URI.
- Read inventory data file keys from the manifest.
- Download or stream each `.csv.gz` data file referenced by the manifest.
- Never guess the inventory `data/` path directly.
- Treat the manifest S3 bucket as the inventory bucket for referenced data file keys.

Inventory configuration expectation:

- Format: CSV GZIP.
- Version scope: current versions only.
- Required fields: bucket, key, last modified date, size, ETag.
- The key field must be URL-decoded before sharding or decrypting.

## Sharding

Each server runs one shard configuration:

- `shard.total`: total shard count.
- `shard.index`: this server's shard index.

Shard ownership is:

```text
SHA-256(decodedObjectKey) unsigned mod shard.total == shard.index
```

Rules:

- Use the decoded S3 object key.
- Do not include the bucket name in the hash input.
- Keep `shard.total` fixed for the whole migration.
- If `shard.total` changes, the migration must be treated as a new shard plan.

## Decrypt Interface

The project must define a decryptor interface for the user to implement with existing sample code:

```java
public interface S3ObjectDecryptor {
    Path decryptToLocal(String bucketName, String prefix) throws DecryptException;
}
```

Contract:

- `bucketName` is the source bucket name.
- `prefix` means one decoded S3 object key, not a bulk prefix.
- The decryptor writes the decrypted object to a local file and returns that file path.
- The migration tool uploads the returned file and then deletes it.
- If the decryptor throws before returning a path, the decryptor implementation owns cleanup of any partial files it created.
- The migration tool must not depend on Amazon S3 Encryption Client directly.

## Upload Behavior

After decrypting one object:

- Upload the returned local file to the target bucket with the same S3 key.
- Treat the target object as a new file.
- Do not copy source metadata, tags, ACLs, content type, cache control, or crypto metadata.
- Do not set explicit SSE headers; rely on the target bucket default SSE configuration.
- Delete the local decrypted file after the upload attempt finishes.
- If upload fails, write the object to `failed.log` and still attempt to delete the local decrypted file.

## State File

Each server keeps local state in `state.json`.

The state file is the authoritative progress record for that shard. It must be written atomically by writing a temporary file and renaming it into place.

The state must track:

- shard total and shard index.
- current job mode.
- source bucket and target bucket.
- baseline manifest URI.
- baseline status.
- baseline inventory data file progress.
- baseline observed maximum `LastModifiedDate`.
- delta watermark.
- delta observed maximum `LastModifiedDate`.
- delta candidate watermark for the current in-progress manifest.
- delta manifest progress.
- success, failed, skipped, and retried counts.
- timestamps for job start, last checkpoint, and job completion.
- last run-level error code, message, and timestamp for operator diagnosis.

Baseline statuses:

- `NOT_STARTED`: no baseline has started for this shard.
- `RUNNING`: baseline is in progress.
- `COMPLETED`: baseline scanned the full manifest and no object-level failures were recorded.
- `COMPLETED_WITH_FAILURES`: baseline scanned the full manifest and at least one object was written to `failed.log`.
- `ABORTED`: baseline did not finish scanning the manifest.

Delta gate:

- `delta` may run only when baseline status is `COMPLETED` or `COMPLETED_WITH_FAILURES`.
- `delta` must fail fast for `NOT_STARTED`, `RUNNING`, or `ABORTED`.
- The user may also inspect `state.json` externally before deciding whether to run delta.

Checkpoint granularity:

- The safe checkpoint is an inventory data file boundary.
- A data file is marked complete only after all object tasks submitted from that file finish.
- If the process crashes in the middle of a data file, restart from that data file and accept duplicate processing.

## Baseline Job

Baseline processes all rows in the supplied baseline manifest that belong to the current shard.

Rules:

- Do not use `LastModifiedDate` to skip baseline rows.
- A baseline run must continue from the next incomplete inventory data file in `state.json`.
- If the full manifest is scanned and object failures exist, mark baseline as `COMPLETED_WITH_FAILURES`.
- If the full manifest is scanned without object failures, mark baseline as `COMPLETED`.
- If the manifest cannot be fully scanned because of a run-level fatal error, mark baseline as `ABORTED`.

## Delta Job

Delta processes a daily current-version inventory manifest after the shard baseline is acceptable.

Rules:

- Check the delta gate before reading the delta manifest.
- Process only rows where `LastModifiedDate >= previousWatermark`.
- Use `>=` rather than `>` to avoid boundary misses.
- Accept duplicate processing.
- Advance the delta watermark only after the whole manifest is scanned successfully.
- If a delta manifest cannot be fully scanned, do not advance the watermark.
- During an in-progress manifest scan, write observed maximum `LastModifiedDate` and candidate watermark at inventory data file checkpoints. These are progress hints and resume inputs, not the committed delta lower bound.

Initial delta watermark:

- After baseline completes, initialize the delta lower bound from an explicitly configured baseline watermark if present.
- If no explicit watermark is configured, initialize from the maximum `LastModifiedDate` actually observed for this shard while scanning the baseline manifest.
- If the baseline shard observes no owned rows, initialize from `Instant.EPOCH`.
- Do not use the inventory folder date or manifest `creationTimestamp` as the delta watermark. Those values describe report generation or delivery timing, not the freshness of object rows.
- The first delta run after baseline must catch objects written or overwritten after the baseline inventory snapshot.
- After each successful delta scan, advance the watermark only to the maximum `LastModifiedDate` actually observed for this shard in that delta run. If the delta run observes no newer owned rows, keep the previous watermark.
- The committed `deltaWatermark` is updated only after the whole manifest succeeds; the in-progress `deltaCandidateWatermark` may be written earlier for visibility and resume safety.

## Failed Log

Object-level failures are written as JSONL.

Each failed record must include:

- `runId`
- `mode`
- `shardTotal`
- `shardIndex`
- `sourceBucket`
- `targetBucket`
- `key`
- `lastModified`
- `etag`
- `size`
- `errorCode`
- `message`
- `timestamp`

Failure examples:

- decryptor exception.
- upload exception.
- local file cleanup exception after upload attempt.
- unreadable inventory row if it can be isolated to a row.

Run-level fatal errors, such as manifest download failure or unreadable manifest JSON, should fail the job and update state rather than being logged as object-level failures.

If appending a failed record would push the failed log over `migration.observability.max-failed-log-bytes`, fail fast. This prevents a systemic decrypt/upload/configuration failure from producing unbounded local logs and filling the disk.

## Retry Job

Retry mode reads one or more failed JSONL files.

Rules:

- For each failed record, use the current source bucket and key unless configuration overrides the bucket.
- Call the same decryptor interface.
- Upload to the target bucket with the same key.
- Delete the local decrypted file after the upload attempt.
- Write remaining failures to a new retry failed log.
- Update success, failed, and retried counters in `state.json`.
- Retry mode does not update baseline status or delta watermarks.

## Logging

The CLI logs operator-visible progress to stdout/stderr through SLF4J:

- job start and completion for baseline, delta, and retry.
- each completed inventory data file with success, failed, skipped, and retried counts.
- intra-file progress while scanning or waiting on workers, controlled by `migration.observability.progress-log-interval`.
- delta watermark initialization and advancement.
- run-level aborts with error details.

## Final Verification

Recommended verification:

- Enable current-version CSV GZIP inventory on the target bucket.
- Compare final source current inventory against final target current inventory.
- Report source keys missing in target.
- Ignore target extra keys.

Do not compare source and target object size as the primary validation because source objects are encrypted ciphertext while target objects are decrypted plaintext.

## Operational Assumptions

- The source bucket inventory is current-version only.
- The target bucket already has default SSE configured.
- The target bucket may have versioning enabled.
- Duplicate target PUT operations are acceptable.
- Local disk loss means that shard may need to restart from an earlier checkpoint or from the beginning.
- The user decides whether a `COMPLETED_WITH_FAILURES` baseline is acceptable enough to start delta.
