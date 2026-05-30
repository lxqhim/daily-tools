# S3 Migration V2 Usage

This tool reads S3 Inventory, decrypts each source object locally, uploads the decrypted file to the target bucket, then deletes the local file.

## Build

```bash
mvn package
```

Runnable jar:

```text
target/s3-migration-v2-0.1.0-SNAPSHOT.jar
```

## Required Inventory

Configure source-bucket S3 Inventory as:

- daily frequency.
- current versions only.
- CSV format.
- GZIP compression.
- fields: bucket, key, last modified date, size, ETag.

Each run receives the `manifest.json` S3 URI. Do not pass a `data/*.csv.gz` URI directly.

## Legacy KMS Decryptor

The built-in decryptor is for old AWS SDK v1 S3 client-side encrypted objects that used `KMSEncryptionMaterialsProvider`.

Enable it with:

```yaml
migration:
  decrypt:
    legacy-kms:
      enabled: true
      kms-key-id: arn:aws:kms:us-east-1:111122223333:key/your-key-id
      crypto-mode: encryption-only
      storage-mode: object-metadata
```

Options:

- `kms-key-id`: KMS key id or ARN used by the old encryption client.
- `crypto-mode`: `encryption-only` by default. Use this for objects written by older `AmazonS3Encryption` clients configured with `CryptoMode.EncryptionOnly`. Use `authenticated-encryption` or `strict-authenticated-encryption` only for objects written with those modes.
- `storage-mode`: `object-metadata` by default. Use `instruction-file` only if the old project stored crypto metadata in separate instruction files.
- `kms-region`: optional. Set this only if the KMS key region differs from `migration.s3.region`.
- Credentials use the AWS SDK v1 default provider chain. On EC2, the job can use the instance profile role; make sure that role has `s3:GetObject` on the source objects and `kms:Decrypt` on the KMS key.

If `legacy-kms.enabled=false` or omitted, no bundled decryptor is registered. Startup fails unless you provide your own Spring `S3ObjectDecryptor` bean on the classpath, so a bad decryptor configuration is caught before scanning inventory.

If startup reports `No S3ObjectDecryptor configured`, either enable the built-in legacy KMS decryptor with `migration.decrypt.legacy-kms.enabled=true` and `migration.decrypt.legacy-kms.kms-key-id`, or package your own implementation of `S3ObjectDecryptor` as a Spring bean.

The legacy KMS decryptor depends on Bouncy Castle because AWS SDK v1 may require the `BC` provider for legacy encryption modes. The runnable jar includes `org.bouncycastle:bcprov-jdk18on`.

## Common Config

You can configure with `application.yml`, environment variables, or command-line overrides.

Important properties:

```yaml
migration:
  job:
    mode: baseline
    run-id: optional-human-readable-id
    initial-watermark: 2026-04-01T00:00:00Z
    delta-lookback: 48h
  s3:
    region: us-east-1
    source-bucket: source-bucket-name
    target-bucket: target-bucket-name
  inventory:
    manifest-uri: s3://inventory-bucket/path/to/manifest.json
  shard:
    total: 8
    index: 0
  paths:
    state: state-shard-0.json
    failed-log: failed-shard-0.log
    retry-failed-log: failed-retry-shard-0.log
    temp-dir: tmp
  worker:
    concurrency: 8
    queue-size: 64
  upload:
    dry-run: false
    dry-run-sample-size: 100
    multipart-threshold-bytes: 134217728
    multipart-part-size-bytes: 67108864
    client-side-kms:
      enabled: false
      kms-key-id: arn:aws:kms:us-east-1:444455556666:key/target-key-id
      crypto-mode: encryption-only
      storage-mode: object-metadata
  observability:
    progress-log-interval: 5m
    max-failed-log-bytes: 10737418240
```

`initial-watermark` is optional. If omitted, baseline completion initializes the delta watermark from the maximum row-level `LastModifiedDate` observed by this shard minus `delta-lookback`.

`delta-lookback` defaults to `48h`. After baseline and each successful delta, the committed watermark is moved to `observedMaxLastModified - deltaLookback`, but never backward during delta advancement. This intentionally repeats recent objects so a later inventory can still catch objects whose `LastModifiedDate` is older than the newest row in an earlier report but was missing from that earlier report.

## Target Client-Side Encryption

By default, target upload is plain S3 upload and the target bucket default SSE applies. The tool does not set explicit SSE headers.

Enable client-side KMS encryption for target uploads only when the target objects must also be readable through the AWS SDK v1 S3 encryption client:

```yaml
migration:
  upload:
    client-side-kms:
      enabled: true
      kms-key-id: arn:aws:kms:us-east-1:444455556666:key/target-key-id
      crypto-mode: encryption-only
      storage-mode: object-metadata
```

`kms-key-id` must be a full KMS key ARN. Alias and bare key id are intentionally rejected for target upload to avoid cross-account key resolution mistakes.

The EC2 instance profile role must be allowed by the target account KMS key policy to use the target key for client-side encryption, including `kms:GenerateDataKey`, `kms:Encrypt`, and `kms:DescribeKey`.

## Decrypt-only Dry Run

Use upload dry-run when you want to validate decrypt output before writing to the target bucket:

```bash
--migration.upload.dry-run=true
```

When enabled:

- the decryptor still downloads and decrypts the source object to a local file.
- the tool logs source bucket, target bucket, key, local file path, and local file size.
- upload to the target bucket is skipped.
- the local decrypted file is retained for manual inspection.
- at most `migration.upload.dry-run-sample-size` eligible objects are decrypted and retained per run.
- after the sample limit is reached, later objects are not downloaded, decrypted, uploaded, or retained.
- the object is counted as `dryRunSuccess`, not `success`, if decrypt produced a readable local file.
- `state.json` is not written or checkpointed, so a later real baseline is not skipped.

The default sample size is `100`. Increase it only when the server has enough temporary disk for the retained decrypted files. Dry-run is intentionally not resumable.

## Baseline

Run one process per shard server. Example for shard `0` of `8`:

```bash
java -jar target/s3-migration-v2-0.1.0-SNAPSHOT.jar \
  --migration.job.mode=baseline \
  --migration.s3.region=us-east-1 \
  --migration.s3.source-bucket=my-source-bucket \
  --migration.s3.target-bucket=my-target-bucket \
  --migration.inventory.manifest-uri=s3://my-inventory-bucket/source/config/2026-04-20T00-00Z/manifest.json \
  --migration.shard.total=8 \
  --migration.shard.index=0 \
  --migration.paths.state=state-shard-0.json \
  --migration.paths.failed-log=failed-shard-0.log \
  --migration.paths.temp-dir=tmp-shard-0 \
  --migration.decrypt.legacy-kms.enabled=true \
  --migration.decrypt.legacy-kms.kms-key-id=arn:aws:kms:us-east-1:111122223333:key/your-key-id
```

Baseline scans the whole manifest for this shard. It does not skip old objects by last modified date.

For long-running Linux servers, prefer the wrapper script instead of hand-writing a long `nohup java ...`
command. The script validates required settings, prints the effective shard/decryptor settings at startup, and
keeps the `nohup` command short:

```bash
nohup env \
  MODE=baseline \
  REGION=us-east-1 \
  SOURCE_BUCKET=my-source-bucket \
  TARGET_BUCKET=my-target-bucket \
  MANIFEST_URI=s3://my-inventory-bucket/source/config/2026-04-20T00-00Z/manifest.json \
  SHARD_TOTAL=8 \
  SHARD_INDEX=0 \
  SOURCE_KMS_KEY_ID=arn:aws:kms:us-east-1:111122223333:key/your-key-id \
  ./scripts/run-migration.sh > migration-shard-0.log 2>&1 &
```

Optional script variables include `STATE_PATH`, `FAILED_LOG_PATH`, `TEMP_DIR`, `CONCURRENCY`,
`QUEUE_SIZE`, `DRY_RUN`, `DRY_RUN_SAMPLE_SIZE`, `DELTA_LOOKBACK`, and `EXTRA_JAVA_OPTS`.

## Delta

Delta is allowed only after the local `state.json` baseline status is `COMPLETED` or `COMPLETED_WITH_FAILURES`.

```bash
java -jar target/s3-migration-v2-0.1.0-SNAPSHOT.jar \
  --migration.job.mode=delta \
  --migration.s3.region=us-east-1 \
  --migration.s3.source-bucket=my-source-bucket \
  --migration.s3.target-bucket=my-target-bucket \
  --migration.inventory.manifest-uri=s3://my-inventory-bucket/source/config/2026-04-21T00-00Z/manifest.json \
  --migration.shard.total=8 \
  --migration.shard.index=0 \
  --migration.paths.state=state-shard-0.json \
  --migration.paths.failed-log=failed-shard-0.log \
  --migration.paths.temp-dir=tmp-shard-0 \
  --migration.decrypt.legacy-kms.enabled=true \
  --migration.decrypt.legacy-kms.kms-key-id=arn:aws:kms:us-east-1:111122223333:key/your-key-id
```

Delta processes rows where `LastModifiedDate >= deltaWatermark`. The committed `deltaWatermark` advances only after the whole manifest is scanned successfully, and advancement keeps the configured `delta-lookback` window.

## Retry

Retry reads one or more failed JSONL logs and writes remaining failures to a separate retry log.

```bash
java -jar target/s3-migration-v2-0.1.0-SNAPSHOT.jar \
  --migration.job.mode=retry \
  --migration.s3.region=us-east-1 \
  --migration.s3.source-bucket=my-source-bucket \
  --migration.s3.target-bucket=my-target-bucket \
  --migration.shard.total=8 \
  --migration.shard.index=0 \
  --migration.paths.state=state-shard-0.json \
  --migration.paths.retry-inputs=failed-shard-0.log \
  --migration.paths.retry-failed-log=failed-retry-shard-0.log \
  --migration.paths.temp-dir=tmp-shard-0 \
  --migration.decrypt.legacy-kms.enabled=true \
  --migration.decrypt.legacy-kms.kms-key-id=arn:aws:kms:us-east-1:111122223333:key/your-key-id
```

For multiple retry inputs, use comma-separated paths:

```bash
--migration.paths.retry-inputs=failed-a.log,failed-b.log
```

## IAM Permissions

Each server role needs at least:

- `s3:GetObject` on the inventory bucket/prefix.
- `s3:GetObject` on the source bucket.
- `s3:PutObject`, `s3:AbortMultipartUpload`, and multipart upload permissions on the target bucket.
- `kms:Decrypt` on the legacy KMS key.

If source objects use instruction files, the role also needs `s3:GetObject` on the instruction-file keys.

## Running in Background

Migrations run for hours or days. Always run in background so the process survives SSH disconnection.

### Option A — nohup (simplest)

```bash
LOG_FILE=/var/log/migration/shard-0.log
mkdir -p "$(dirname "$LOG_FILE")"

nohup java -jar target/s3-migration-v2-0.1.0-SNAPSHOT.jar \
  --migration.job.mode=baseline \
  --migration.s3.source-bucket=my-source-bucket \
  --migration.s3.target-bucket=my-target-bucket \
  --migration.inventory.manifest-uri=s3://my-inventory-bucket/.../manifest.json \
  --migration.shard.total=8 \
  --migration.shard.index=0 \
  --migration.paths.state=state-shard-0.json \
  --migration.paths.failed-log=failed-shard-0.log \
  --migration.paths.temp-dir=tmp-shard-0 \
  --migration.decrypt.legacy-kms.enabled=true \
  --migration.decrypt.legacy-kms.kms-key-id=arn:aws:kms:us-east-1:111122223333:key/your-key-id \
  >> "$LOG_FILE" 2>&1 &

echo "PID: $!"
```

Record the PID. The process continues after you disconnect.

### Option B — screen (can reattach)

```bash
screen -S migration-shard-0

# Inside the screen session, run the java command (output goes to terminal and optionally a log file):
java -jar target/s3-migration-v2-0.1.0-SNAPSHOT.jar ... >> shard-0.log 2>&1

# Detach without stopping: Ctrl+A then D
# Reattach later:
screen -r migration-shard-0
```

### Monitoring

```bash
# Check process is still running
ps aux | grep s3-migration

# Tail live log
tail -f /var/log/migration/shard-0.log

# Check progress counters (updated after each inventory data file)
cat state-shard-0.json | python3 -m json.tool

# Count failures so far
wc -l failed-shard-0.log

# Check temp disk usage
du -sh tmp-shard-0/
```

### Before Starting

Estimate peak temp disk usage: `worker.concurrency × largest-object-size`. With 8 workers and 1 GB objects, reserve at least 8 GB on the temp disk. Point `migration.paths.temp-dir` to a volume with enough space if the default working directory is constrained.

## Operational Notes

- Keep `shard.total` fixed for the whole migration.
- Use a different `state.json`, `failed.log`, and temp directory per shard server.
- The target bucket default SSE is used; the tool does not set explicit SSE headers.
- Failed logs are capped by `migration.observability.max-failed-log-bytes` to avoid filling the disk during systemic failures.
- Intra-file progress logs are emitted every `migration.observability.progress-log-interval`, so a very large inventory file should not be silent for an hour.
