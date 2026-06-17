# s3-lambda

Standalone Java Lambda for Amazon S3 Batch Operations.

The handler decrypts one S3 Batch Operations task with the legacy AWS SDK v1 `AmazonS3Encryption` client, writes the decrypted object to local `/tmp`, uploads it to the target bucket, and deletes the local temp file.

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
TARGET_KEY_PREFIX=
TEMP_DIR=/tmp/s3-batch-decrypt
CRYPTO_MODE=ENCRYPTION_ONLY
CRYPTO_STORAGE_MODE=OBJECT_METADATA
RESULT_STRING_MAX_LENGTH=1024
```

## IAM Notes

The Lambda execution role needs read access to source objects and versions, write access to the target bucket, and KMS decrypt permission on the source client-side encryption key.

The S3 Batch Operations role needs permission to invoke the Lambda function and read the Batch manifest.
