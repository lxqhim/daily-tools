# S3 Migration V2 Flow

## Timeline Overview

Define `T` as the cutover date.

The normal migration window starts at `T-30`, thirty calendar days before cutover.

High-level timeline:

- Before `T-30`: enable daily current-version CSV GZIP inventory for the source bucket.
- `T-30`: choose the baseline inventory manifest and start `baseline` on all shard servers.
- `T-30` to `T`: keep running or resuming `baseline` until each shard completes. A shard may start `delta` only after its own baseline state is `COMPLETED` or `COMPLETED_WITH_FAILURES`.
- After a shard baseline completes and before `T`: run daily `delta` for that shard with each new daily manifest.
- `T`: stop new writes to the source bucket.
- `T+1` to `T+3`: keep running post-cutover `delta` with each new inventory, but do not treat these as final by default.
- `T+4`: run the default final `delta` candidate. This gives the source inventory pipeline extra time to reflect writes stopped at `T`.
- After final delta: run accepted retries, generate target inventory, and verify source-current keys against target-current keys.

If baseline takes longer than expected, do not skip baseline work. Continue baseline first. Delta for that shard starts only after baseline reaches an allowed terminal state.

`T-30` is the intended baseline start point, not a hard data correctness boundary. The baseline job still scans the whole supplied baseline manifest for the shard.

The final delta is defined by inventory freshness, not by the calendar label alone. `T+4` is the default final candidate. If the `T+4` inventory is delayed or does not reflect the stopped-write state, continue daily delta runs until the operator has a final source current-version inventory that is acceptable for verification.

## 1. Inventory Preparation

Before running this tool, enable S3 Inventory for the source bucket.

Expected source inventory setup:

- Frequency: daily.
- Object versions: current versions only.
- Output format: CSV.
- Compression: GZIP.
- Destination: an inventory bucket and prefix controlled by the migration operator.

The inventory output includes a `manifest.json` file and one or more compressed CSV data files. The operator gives this tool the `manifest.json` S3 URI for each run.

The tool does not list the source bucket. All object candidates come from S3 Inventory.

## 2. Daily Operator Input

For each run, the operator chooses:

- job mode: `baseline`, `delta`, or `retry`.
- source bucket.
- target bucket.
- inventory manifest S3 URI for baseline or delta.
- shard total.
- shard index.
- local state path.
- local failed log path.
- local temp directory.
- worker concurrency.

Each server should use a stable shard index. For example, with 8 servers:

- server 0 runs `shard.index=0`, `shard.total=8`.
- server 1 runs `shard.index=1`, `shard.total=8`.
- ...
- server 7 runs `shard.index=7`, `shard.total=8`.

Do not change `shard.total` halfway through the migration unless the whole shard plan is restarted.

## 3. EC2 Capacity Plan

Recommended default production fleet:

- Instance type: `c7i.4xlarge`.
- Count: 8 servers.
- Shard layout: `shard.total=8`, with one stable `shard.index` per server.
- Initial worker setting: `migration.worker.concurrency=8`.
- JVM heap: start with `-Xms8g -Xmx12g`; increase only if GC or heap telemetry shows pressure.
- Local disk: attach a dedicated EBS volume for state, failed logs, and temp files. Start with 1 TiB `gp3`; increase if the largest object size or retry log size requires it.

Why this is the default:

- The job is a mixed network, CPU, KMS, and disk-temp workload. It streams inventory, downloads one source object, decrypts locally, uploads one target object, and deletes the temp file.
- `c7i.4xlarge` gives 16 vCPU and 32 GiB memory per server, which is enough headroom for 8 decrypt/upload workers without using memory-optimized instances.
- 8 servers keep shard fan-out simple and match the intended 4-8 server design while giving enough parallelism for 800M objects.
- Larger instances do not remove KMS or S3 request-rate limits. Scale instance count or worker concurrency only after checking throttling metrics.

Sizing alternatives:

- Conservative start: 4 x `c7i.4xlarge`, `shard.total=4`, `worker.concurrency=8`. Use this only for a dry run, small object counts, or when baseline can take materially longer.
- Recommended production: 8 x `c7i.4xlarge`, `shard.total=8`, `worker.concurrency=8`.
- If CPU is consistently above 75%, network is not saturated, and KMS throttling is absent: try `worker.concurrency=12` or move to `c7i.8xlarge`.
- If network or EBS throughput is the bottleneck: move to `c7i.12xlarge` before increasing worker count aggressively.
- If heap or native memory pressure appears despite streaming fixes: use `m7i.4xlarge` as the memory-heavier alternative, but keep the same shard plan.

Do not use `c7i-flex` for the main production baseline. Flex can be fine for short tests, but a long migration may keep CPU and network busy for many hours; use standard `c7i` for steadier capacity.

Before full production, run a 1% sample or a bounded prefix test:

1. Start with 2 servers of the chosen type and the same command-line shape as production.
2. Measure per-server objects/sec, MB/sec download, MB/sec upload, CPU, heap, GC pause, EBS queue depth, KMS throttling, and failed object rate.
3. Estimate full baseline duration:

   ```text
   estimated_seconds = total_objects / (measured_objects_per_second_per_server * server_count)
   ```

4. If the estimate is too slow and KMS/S3 throttling is not visible, scale to 8 servers.
5. If KMS throttling appears, reduce `worker.concurrency` first. More EC2 will make throttling worse.

Operational rules:

- Place all migration EC2 instances in the same AWS Region as the source and target buckets.
- Use the same instance type for all shards unless there is a clear reason not to. Uneven capacity makes completion time harder to reason about.
- Keep `state.json` and `failed.log` on durable EBS, not on instance-only ephemeral storage.
- On replacement, restore or attach the shard's state and failed logs, then restart with the same `shard.index`.
- Treat KMS and S3 throttling as fleet-level signals. If throttling appears, tune total concurrency across all servers, not only a single server.

## 4. Startup Flow

On startup, the tool loads configuration and validates:

- mode is valid.
- source bucket is present.
- target bucket is present for `baseline`, `delta`, and `retry`.
- shard index is in range.
- state path is writable.
- failed log path is writable.
- temp directory exists or can be created.
- manifest URI is present for `baseline` and `delta`.
- retry input failed log path is present for `retry`.

Then it loads `state.json` if it exists.

If `state.json` does not exist:

- initialize shard state.
- set baseline status to `NOT_STARTED`.
- set counts to zero.
- write the initial state atomically.

If `state.json` exists:

- verify the stored shard total and index match the current configuration.
- verify source and target bucket match, unless an explicit override is configured.
- resume from the recorded checkpoint.

## 5. Manifest Download Flow

For `baseline` and `delta`, the tool receives a URI like:

```text
s3://inventory-bucket/some/prefix/manifest.json
```

The tool:

1. Parses the URI into inventory bucket and manifest key.
2. Downloads the manifest JSON.
3. Reads the list of inventory data files from the manifest.
4. Stores the manifest URI and manifest metadata in `state.json`.
5. Processes data files in manifest order.

The tool must not assume a fixed `data/` path. The manifest is the source of truth.

## 6. Inventory Data File Flow

Each manifest usually points to many `.csv.gz` data files.

For each data file:

1. Check whether the file index is already completed in `state.json`.
2. If completed, skip to the next data file.
3. Stream the `.csv.gz` object from the inventory bucket.
4. Decompress with GZIP while reading.
5. Parse CSV rows one by one.
6. For each row, build an object candidate.
7. Submit owned candidates to the worker pool.
8. At end of file, wait for all tasks submitted from that file to finish.
9. Update success, failure, and skipped counts.
10. Update observed maximum `LastModifiedDate` and candidate watermark for the current mode.
11. Mark that data file complete in `state.json`.
12. Atomically write `state.json`.

Checkpointing happens at data file boundaries. If the process crashes halfway through a CSV file, the next run reprocesses that whole CSV file. This is intentional because duplicate target uploads are acceptable and file-boundary checkpointing avoids losing queued work.

If one CSV row is malformed but the row can be isolated, the reader records an `INVENTORY_ROW_PARSE_FAILED` entry in `failed.log`, counts it as a failed row for the owning shard, and continues parsing later rows. Schema-level failures or unreadable gzip/data files remain run-level failures.

While processing a large data file, the reader emits an intra-file progress log every `migration.observability.progress-log-interval`. The progress line includes elapsed time, scanned rows, submitted rows, in-flight workers, current file counters, and observed maximum `LastModifiedDate`.

## 7. CSV Row Flow

For each CSV row:

1. Read bucket, key, last modified date, size, and ETag.
2. URL-decode the key field.
3. Ignore rows whose bucket does not match the configured source bucket, if the bucket column is present.
4. Apply mode-specific filters.
5. Apply hash sharding.
6. If the row belongs to this shard, submit it for object processing.

Mode-specific filters:

- `baseline`: no `LastModifiedDate` filter.
- `delta`: process only if `LastModifiedDate >= previousWatermark`.

Shard filter:

```text
SHA-256(decodedObjectKey) unsigned mod shard.total == shard.index
```

Rows not owned by this shard are counted as skipped.

## 8. Baseline Flow

Baseline is the first full pass for a shard.

Startup behavior:

1. Load `state.json`.
2. If baseline status is `NOT_STARTED`, mark it `RUNNING`.
3. If baseline status is `RUNNING` or `ABORTED`, resume from the next incomplete data file.
4. If baseline status is already `COMPLETED` or `COMPLETED_WITH_FAILURES`, do not redo it unless the operator explicitly resets state.

Processing behavior:

1. Download and parse the supplied baseline manifest.
2. Process every inventory data file assigned to the current shard.
3. Do not skip old objects by last modified date.
4. Write object-level failures to `failed.log`.
5. Continue after object-level failures.
6. Abort only for run-level failures that prevent the manifest from being fully scanned.

Completion behavior:

- If every data file in the manifest was scanned and there were no object-level failures, set baseline status to `COMPLETED`.
- If every data file in the manifest was scanned and at least one object-level failure occurred, set baseline status to `COMPLETED_WITH_FAILURES`.
- If the manifest was not fully scanned, set baseline status to `ABORTED`.

The operator can inspect `state.json` and `failed.log` to decide whether `COMPLETED_WITH_FAILURES` is acceptable.

## 9. Delta Flow

Delta is a daily catch-up pass after baseline.

Before doing any manifest work, the tool checks the baseline status in `state.json`.

Allowed baseline states:

- `COMPLETED`
- `COMPLETED_WITH_FAILURES`

Rejected baseline states:

- `NOT_STARTED`
- `RUNNING`
- `ABORTED`

If rejected, delta exits before downloading or processing the delta manifest.

When allowed:

1. Load the previous delta watermark.
2. If no delta watermark exists, use the configured initial watermark; if none exists, use `Instant.EPOCH`.
3. Download and parse the supplied daily manifest.
4. Stream every inventory data file.
5. Submit only rows whose `LastModifiedDate >= previousWatermark`.
6. Apply the same hash shard rule.
7. Process matching objects through the same decrypt-upload-cleanup flow.
8. Write object-level failures to the delta failed log.
9. Track the maximum `LastModifiedDate` actually observed for owned rows in this delta run.
10. At each completed data file boundary, write `deltaObservedMaxLastModified` and `deltaCandidateWatermark` to `state.json`.
11. After the whole manifest is scanned, advance the committed `deltaWatermark` to that observed maximum only if it is newer than the previous watermark.
12. Atomically write the updated state.

The comparison uses `>=`, not `>`, so objects on the boundary may be processed twice. This avoids missing objects with equal timestamps.

If the delta run aborts before scanning the full manifest, do not advance the watermark. The next run should retry from the previous safe watermark.

If the delta run resumes after some data files were already checkpointed, the saved `deltaObservedMaxLastModified` is loaded and folded into the candidate watermark. This prevents losing the observed maximum from files that are skipped on resume.

The inventory report date, folder date, and manifest `creationTimestamp` are not used as watermarks. For example, an inventory delivered around `T-28` may still only reflect object rows up to `T-30` or `T-31`; using the report date would skip objects. The watermark is based on row-level `LastModifiedDate` values that the shard actually scanned, or on an operator-provided initial watermark.

Run-level delta failures write `lastErrorCode`, `lastErrorMessage`, and `lastErrorAt` into `state.json` and log the error. Delta has no separate terminal status; the committed `deltaWatermark` remains unchanged on failure.

## 10. Single Object Processing Flow

Each owned object is processed by a worker.

For one object:

1. Receive source bucket and decoded object key.
2. Call the user-provided decryptor with:

   ```text
   decryptToLocal(sourceBucket, decodedObjectKey)
   ```

3. The decryptor downloads and decrypts the source object using user-supplied logic.
4. The decryptor writes the decrypted content to a local file.
5. The decryptor returns the local file path.
6. The migration tool uploads that local file to the target bucket using the same object key.
7. The target upload is treated as a new file.
8. The migration tool does not copy source metadata, tags, ACLs, or content type.
9. The migration tool relies on target bucket default SSE.
10. In a finally-style cleanup step, the migration tool deletes the returned local file path.

If `migration.upload.dry-run=true`:

- steps 1 through 5 still run.
- the tool logs source bucket, target bucket, key, local file path, and local file size.
- upload is skipped.
- local cleanup is skipped so the decrypted file remains available for inspection.
- only the first `migration.upload.dry-run-sample-size` eligible objects are submitted for decrypt.
- after the sample limit is reached, the tool stops reading later inventory data files and does not decrypt more objects.
- the object is counted as `dryRunSuccess`, not `success`, if decrypt produced a readable local file.
- production state checkpointing is disabled; the tool does not mark data files complete or mark baseline complete.
- dry-run is not resumable. The default sample size is `100`, so a full baseline dry-run cannot silently retain millions of local files.

If decryptor throws before returning a path:

- record an object-level failure.
- the decryptor implementation is responsible for cleaning its partial local files.

If upload fails after a path is returned:

- record an object-level failure.
- still attempt to delete the local decrypted file.

If local cleanup fails:

- record or warn about the cleanup failure.
- continue processing other objects.

## 11. Failed Log Flow

Object-level failures are appended to a JSONL file.

One failed object equals one JSON object line.

The failed record includes:

- run id.
- mode.
- shard total.
- shard index.
- source bucket.
- target bucket.
- object key.
- last modified date from inventory, if available.
- ETag from inventory, if available.
- size from inventory, if available.
- error code.
- error message.
- timestamp.

Object-level failures do not stop the job. They affect final status:

- baseline with object failures becomes `COMPLETED_WITH_FAILURES` if the whole manifest was scanned.
- delta with object failures keeps the same watermark rule: watermark advances only if the whole manifest was scanned.

If a failed log reaches `migration.observability.max-failed-log-bytes`, the next append fails the job. Operators should treat this as a systemic failure signal, inspect the recent failed records, fix the cause, and resume or retry with an explicit decision.

Run-level failures are different. Examples:

- cannot download manifest.
- manifest JSON cannot be parsed.
- inventory data file cannot be read.
- state file cannot be written.

Run-level failures stop the job and leave the state as not safely complete.

## 12. Retry Flow

Retry mode is independent from baseline and delta watermarks.

Input:

- one or more failed JSONL files.
- source bucket.
- target bucket.
- shard configuration, if the operator wants to preserve shard ownership.

For each failed record:

1. Read the key.
2. Optionally verify the record belongs to the current shard.
3. Call the same decryptor.
4. Upload the returned local file to the target bucket with the same key.
5. Delete the local file after the upload attempt.
6. Write any remaining failure to a new retry failed log.
7. Update retry, success, and failure counters in `state.json`.

Retry does not mark baseline complete and does not advance delta watermark.

## 13. Resume Flow

When a job restarts:

1. Load `state.json`.
2. Verify shard and bucket configuration.
3. Find the next incomplete inventory data file for the current mode.
4. Reprocess from that file.
5. Accept duplicate uploads from the partially completed file.

If the previous process died mid-file, the data file was not checkpointed and is intentionally replayed.

If the previous process died after a file checkpoint was written, the next run starts from the following file.

## 14. Cutover Flow

The intended operational sequence is anchored around cutover date `T`.

Before `T-30`:

1. Configure source current-version daily inventory.
2. Confirm the inventory destination bucket and prefix.
3. Confirm all shard servers have stable `shard.total` and `shard.index`.

At `T-30`:

1. Wait for the daily source inventory manifest selected as the baseline manifest.
2. Start `baseline` across all shard servers with that manifest.
3. Keep each shard running or resumable until its baseline manifest is fully scanned.

From `T-30` to `T`:

1. Inspect each shard's `state.json` and `failed.log`.
2. If a shard is `COMPLETED` or `COMPLETED_WITH_FAILURES`, allow that shard to run `delta`.
3. If a shard is `NOT_STARTED`, `RUNNING`, or `ABORTED`, do not run `delta` for that shard.
4. For eligible shards, run daily `delta` with each new source inventory manifest.
5. Continue retry jobs for accepted failed logs if needed.

At `T`:

1. Stop new writes to the source bucket.
2. Keep source bucket readable for decrypt and migration jobs.
3. Do not switch readers or downstream users until final delta and verification are complete.

From `T+1` to `T+3`:

1. Use each source current-version inventory generated after cutover.
2. Run `delta` for all eligible shards each day.
3. Treat these as post-cutover catch-up passes, not as final by default.

At `T+4`:

1. Use the `T+4` source current-version inventory as the default final delta candidate.
2. Run `delta` for all eligible shards.
3. If this inventory is accepted as reflecting the stopped-write state at `T`, mark it as the final source inventory for verification.
4. If this inventory is delayed or not accepted as final, continue daily delta runs until a final source inventory is accepted.

After final delta:

1. Run retry jobs for accepted failed logs if needed.
2. Enable or use target current-version inventory.
3. Compare final source current inventory to target current inventory.
4. Switch readers or downstream users to the target bucket after missing source-current keys are resolved or explicitly accepted.

The target bucket may contain extra keys caused by source deletes during the migration window. Extra target keys are ignored by this migration plan.

## 15. Final Verification Flow

Recommended verification uses inventories, not per-object HEAD requests.

1. Enable current-version CSV GZIP inventory on the target bucket.
2. Generate final source and target inventory snapshots.
3. Compare source current keys to target current keys.
4. Report keys present in source but missing in target.
5. Ignore keys present in target but missing in source.
6. Do not use object size as the primary equality check because source is encrypted ciphertext and target is decrypted plaintext.

If missing source keys are found:

- create a retry input file from those keys.
- run retry mode.
- regenerate or refresh the verification report.

## 16. Local Disk Flow

The decryptor writes decrypted files to local disk. The migration tool uploads and deletes them.

Operational expectations:

- temp directory must have enough space for concurrent decrypted files.
- worker concurrency must be sized according to disk capacity.
- if a worker uploads a large file, that local file remains until upload finishes.
- if cleanup fails, operators should investigate disk pressure and cleanup warnings.

The migration tool should not keep decrypted files as durable output.

## 17. Concurrency Flow

The reader and worker pool are separated.

Reader responsibilities:

- stream manifest data files.
- parse rows.
- filter rows.
- submit owned object tasks to a bounded queue.

Worker responsibilities:

- call decryptor.
- upload local file.
- cleanup local file.
- report success or failure.

The bounded queue prevents the reader from submitting unlimited work and filling memory.

State checkpointing waits for all workers submitted from the current inventory data file before marking that file complete.

State writes are owned by the reader thread at data file boundaries. Worker threads do not mutate `state.json`; they only return object results and append failures through a synchronized failed-log writer.

One worker pool is shared for the lifetime of a baseline or delta job. Data-file checkpointing still waits for all tasks submitted from the current inventory data file before moving to the next file.
