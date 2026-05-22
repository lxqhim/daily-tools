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
      crypto-mode: authenticated-encryption
      storage-mode: object-metadata
```

Options:

- `kms-key-id`: KMS key id or ARN used by the old encryption client.
- `crypto-mode`: `authenticated-encryption` by default. This is the compatibility mode for reading objects written by the old v1 encryption client.
- `storage-mode`: `object-metadata` by default. Use `instruction-file` only if the old project stored crypto metadata in separate instruction files.
- `kms-region`: optional. Set this only if the KMS key region differs from `migration.s3.region`.

If `legacy-kms.enabled=false` or omitted, no bundled decryptor is registered. Startup fails unless you provide your own Spring `S3ObjectDecryptor` bean on the classpath, so a bad decryptor configuration is caught before scanning inventory.

If startup reports `No S3ObjectDecryptor configured`, either enable the built-in legacy KMS decryptor with `migration.decrypt.legacy-kms.enabled=true` and `migration.decrypt.legacy-kms.kms-key-id`, or package your own implementation of `S3ObjectDecryptor` as a Spring bean.

The legacy KMS decryptor depends on Bouncy Castle because AWS SDK v1 requires the `BC` provider for authenticated encryption. The runnable jar includes `org.bouncycastle:bcprov-jdk18on`.

## Common Config

You can configure with `application.yml`, environment variables, or command-line overrides.

Important properties:

```yaml
migration:
  job:
    mode: baseline
    run-id: optional-human-readable-id
    initial-watermark: 2026-04-01T00:00:00Z
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
  observability:
    progress-log-interval: 5m
    max-failed-log-bytes: 10737418240
```

`initial-watermark` is optional. If omitted, baseline completion initializes the delta watermark from the maximum row-level `LastModifiedDate` observed by this shard.

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

Delta processes rows where `LastModifiedDate >= deltaWatermark`. The committed `deltaWatermark` advances only after the whole manifest is scanned successfully.

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

## Operational Notes

- Keep `shard.total` fixed for the whole migration.
- Use a different `state.json`, `failed.log`, and temp directory per shard server.
- The target bucket default SSE is used; the tool does not set explicit SSE headers.
- Failed logs are capped by `migration.observability.max-failed-log-bytes` to avoid filling the disk during systemic failures.
- Intra-file progress logs are emitted every `migration.observability.progress-log-interval`, so a very large inventory file should not be silent for an hour.
