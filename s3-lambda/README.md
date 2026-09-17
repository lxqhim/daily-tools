# s3-lambda

Standalone Java Lambdas for Amazon S3 Batch Operations.

The handler decrypts one S3 Batch Operations task with the legacy AWS SDK v1 `AmazonS3Encryption` client, writes the decrypted object to local `/tmp`, uploads it to the target bucket, and deletes the local temp file.

The package also includes a server-side key-rename copy handler for same-bucket S3 migrations.

## Build

```bash
mvn test
mvn package
```

The deployable shaded jar is:

```text
target/s3-lambda-0.1.0-SNAPSHOT-lambda.jar
```

See [USAGE.md](USAGE.md) for Lambda settings, environment variables, IAM notes, and package size checks.

## Lambda Handler

```text
com.dailytools.s3migration.batchlambda.S3BatchDecryptCopyHandler::handleRequest
```

## Key Rename Lambda

The same package also contains a Lambda for copying objects to a renamed S3 key without
decrypting, downloading, uploading, or deleting the original object. It is intended for
S3 Batch Operations manifests containing keys such as `HK/XXX/BAR/...`.

Handler:

```text
com.dailytools.s3migration.batchlambda.S3BatchKeyRenameHandler::handleRequest
```

Required environment variables:

```text
RENAME_SOURCE_SEGMENT=BAR
RENAME_TARGET_SEGMENT=BAR_PRINT
```

Optional environment variables:

```text
AWS_REGION=us-east-1
RENAME_SOURCE_SEGMENT_INDEX=3
RESULT_STRING_MAX_LENGTH=1024
```

For each manifest object whose decoded key exactly matches
the configured source segment at `RENAME_SOURCE_SEGMENT_INDEX`, the Lambda replaces it
with `RENAME_TARGET_SEGMENT` and issues an S3 server-side `CopyObject` in the same bucket.
The index is one-based and counts slash-delimited key segments; its default is `3`. For example,
`HK/XXX/BAR/file.txt` becomes `HK/XXX/BAR_PRINT/file.txt`. Objects outside that structure
are reported as permanent failures and are not copied. The source object is never deleted.

## Environment Variables

Required:

```text
SOURCE_KMS_KEY_ID=arn:aws:kms:us-east-1:111122223333:key/source-key-id
TARGET_BUCKET=target-bucket-name
```

Optional:

```text
AWS_REGION=us-east-1
SOURCE_REGION=us-east-1
TARGET_REGION=us-east-1
SOURCE_KMS_REGION=us-east-1
TARGET_KMS_KEY_ID=
TARGET_KEY_PREFIX=
TEMP_DIR=/tmp/s3-batch-decrypt
CRYPTO_MODE=ENCRYPTION_ONLY
CRYPTO_STORAGE_MODE=OBJECT_METADATA
RESULT_STRING_MAX_LENGTH=1024
COPY_METADATA_KEYS=
METADATA_COPY_DEBUG=false
```

Set `TARGET_KMS_KEY_ID` only when the target bucket policy requires the request to include an explicit SSE-KMS key id. When empty, the target bucket default encryption is used. Prefer a KMS alias ARN if the target key may change.
Set `COPY_METADATA_KEYS` only when selected source metadata should be migrated. Multiple keys use commas, for example `content-type,cache-control,x-amz-meta-owner`. When the value is empty, no metadata is migrated.
Set `METADATA_COPY_DEBUG=true` only for small troubleshooting runs; it logs metadata key names, not metadata values.

## IAM Notes

The Lambda execution role needs read access to source objects and versions, write access to the target bucket, and KMS decrypt permission on the source client-side encryption key.

The S3 Batch Operations role needs permission to invoke the Lambda function and read the Batch manifest.
