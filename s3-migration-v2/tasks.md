# S3 Migration V2 Tasks

## Task 1: Project Skeleton

- Create Maven Spring Boot CLI project structure.
- Configure Java 21.
- Add application configuration binding for job, S3, shard, state, logging, temp, and worker settings.
- Add a smoke test that the application context starts for CLI mode.

Acceptance criteria:

- `mvn test` runs with an empty CLI skeleton.
- Configuration validation rejects missing required values.

## Task 2: S3 URI and Manifest Reader

- Implement S3 URI parsing for `s3://bucket/key`.
- Implement manifest download from the inventory bucket.
- Parse manifest JSON and extract inventory data file keys.
- Persist manifest URI and file list metadata in state.

Acceptance criteria:

- Unit tests parse valid and invalid S3 URIs.
- Unit tests parse sample manifest JSON.
- Manifest reader never guesses `data/*.csv.gz`.

## Task 3: CSV GZIP Inventory Parser

- Stream `.csv.gz` inventory files.
- Parse required columns: bucket, key, last modified date, size, ETag.
- URL-decode object keys.
- Expose rows as a streaming iterator or callback.
- Report isolated row-level parse failures without aborting the full data file.

Acceptance criteria:

- Tests cover URL-encoded keys.
- Tests cover malformed rows.
- Tests cover continuing after an isolated malformed row.
- Tests prove parser does not require loading a whole CSV file into memory.

## Task 4: Hash Sharding

- Implement shard ownership with `SHA-256(decodedObjectKey) unsigned mod shard.total`.
- Validate shard index range.
- Keep hashing independent from source bucket.

Acceptance criteria:

- Same key always maps to same shard.
- Different shard indexes do not both own the same key.
- Invalid shard configuration fails validation.

## Task 5: State Model and Atomic Store

- Define `state.json` model.
- Implement atomic state writes with temp file plus rename.
- Track baseline status, manifest progress, delta watermark, and counters.
- Use inventory data file boundary checkpointing.

Acceptance criteria:

- Tests cover first-run state creation.
- Tests cover atomic write behavior.
- Tests cover resume from next incomplete data file.
- Tests cover baseline statuses: `NOT_STARTED`, `RUNNING`, `COMPLETED`, `COMPLETED_WITH_FAILURES`, `ABORTED`.

## Task 6: Failed JSONL Logger

- Implement append-only JSONL failed log writer.
- Include run id, mode, shard, source bucket, target bucket, key, inventory fields, error code, message, and timestamp.
- Implement failed log reader for retry mode.
- Use failed log entries for isolated malformed inventory rows when they can be assigned to a shard.
- Enforce a configurable failed log size limit and fail fast when exceeded.

Acceptance criteria:

- Tests write and read JSONL records.
- Tests preserve keys with special characters.
- Tests handle multiple failed log input files for retry.
- Tests fail fast when the failed log size limit would be exceeded.

## Task 7: Decryptor Interface

- Define `S3ObjectDecryptor`.
- Define `DecryptException`.
- Add a test fake decryptor that writes predictable local files.
- Do not implement the real user decrypt logic.

Acceptance criteria:

- Tests can inject fake decryptor.
- Interface contract is documented in code comments or project docs.
- No AWS encryption client dependency is required for decrypt logic.

## Task 8: Target Upload and Local Cleanup

- Upload returned local file to the target bucket with the same object key.
- Treat target object as a new file.
- Do not copy source metadata, tags, ACLs, or content type.
- Delete returned local file after every upload attempt.
- Support configurable multipart threshold for large files.

Acceptance criteria:

- Tests cover upload success and cleanup.
- Tests cover upload failure and cleanup.
- Tests cover cleanup failure logging.
- Tests verify no source metadata copy path is used.

## Task 9: Single Object Processor

- Compose decryptor, uploader, cleanup, and failed logger.
- Return terminal object result: success, failed, or skipped.
- Ensure decryptor exceptions become object-level failures.

Acceptance criteria:

- Tests cover decrypt success.
- Tests cover decrypt exception.
- Tests cover upload exception.
- Tests cover file cleanup after upload attempt.

## Task 10: Baseline Job

- Implement baseline startup and state transition.
- Process every data file in the baseline manifest.
- Submit shard-owned rows to workers.
- Wait for all workers from a data file before checkpointing that file.
- Mark final baseline status according to object failures and run-level completion.

Acceptance criteria:

- Tests cover first baseline run.
- Tests cover resume after partial data file progress.
- Tests cover `COMPLETED` with zero failures.
- Tests cover `COMPLETED_WITH_FAILURES` after full scan with object failures.
- Tests cover `ABORTED` after run-level failure.

## Task 11: Delta Job

- Implement baseline status gate for delta.
- Allow delta only for `COMPLETED` and `COMPLETED_WITH_FAILURES`.
- Filter rows by `LastModifiedDate >= previousWatermark`.
- Advance watermark only after full manifest scan.
- Base watermark advancement on observed row-level `LastModifiedDate`, not inventory manifest creation time.

Acceptance criteria:

- Tests reject delta for `NOT_STARTED`, `RUNNING`, and `ABORTED`.
- Tests allow delta for `COMPLETED` and `COMPLETED_WITH_FAILURES`.
- Tests cover inclusive watermark boundary.
- Tests verify watermark is not advanced after aborted run.
- Tests verify manifest creation time is not used as a watermark.

## Task 12: Retry Job

- Read failed JSONL input files.
- Reprocess each failed key through decrypt, upload, and cleanup.
- Write remaining failures to a new retry failed log.
- Do not modify baseline status or delta watermark.
- Update retry attempt counters in state.

Acceptance criteria:

- Tests cover successful retry.
- Tests cover retry failure written to retry failed log.
- Tests verify baseline and delta state are not advanced by retry.
- Tests verify retry counters are written to state.

## Task 13: Worker Pool and Backpressure

- Add bounded queue worker execution.
- Make worker count configurable.
- Keep file-level checkpointing safe by waiting for all tasks from a data file before marking it complete.
- Reuse one worker pool across all data files in a job.
- Emit configurable intra-file progress logs while scanning or waiting on workers.

Acceptance criteria:

- Tests cover all submitted tasks completing before checkpoint.
- Tests cover worker failure counted as object-level failure.
- Tests cover bounded queue behavior at a small queue size.

## Task 14: CLI Packaging and Run Documentation

- Package executable jar.
- Document baseline, delta, and retry command examples.
- Document required IAM permissions.
- Document local disk sizing considerations.
- Document final inventory-based verification approach.

Acceptance criteria:

- `mvn package` creates runnable jar.
- README or operations doc explains how to run each mode.
- Documentation states that real decryptor implementation is user-owned.

## Task 15: End-to-End Test Harness

- Build a local or mocked end-to-end test with fake inventory files and fake decryptor.
- Cover baseline followed by delta followed by retry.
- Cover duplicate processing after restart from a data file checkpoint.

Acceptance criteria:

- End-to-end test passes without real AWS credentials.
- Test verifies uploaded target keys.
- Test verifies failed logs and state transitions.

## Implementation Order

Recommended order:

1. Project skeleton.
2. Config validation.
3. S3 URI and manifest parser.
4. CSV GZIP parser.
5. Sharding.
6. State store.
7. Failed logger.
8. Decryptor interface and fake decryptor.
9. Object processor.
10. Baseline job.
11. Delta job.
12. Retry job.
13. Worker pool.
14. Packaging and documentation.
15. End-to-end test harness.

Follow TDD for each task:

1. Write failing tests.
2. Implement the minimum code to pass.
3. Refactor while keeping tests green.
