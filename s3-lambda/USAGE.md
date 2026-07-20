# Usage

## Build And Test

```bash
cd s3-lambda
mvn test
mvn package
```

The Lambda deployment artifact is:

```text
target/s3-lambda-0.1.0-SNAPSHOT-lambda.jar
```

Check the packaged jar size:

```bash
ls -lh target/s3-lambda-0.1.0-SNAPSHOT-lambda.jar
du -h target/s3-lambda-0.1.0-SNAPSHOT-lambda.jar
```

Check the approximate uncompressed size:

```bash
rm -rf /tmp/s3-lambda-unpacked
mkdir -p /tmp/s3-lambda-unpacked
cd /tmp/s3-lambda-unpacked
jar xf /path/to/s3-lambda/target/s3-lambda-0.1.0-SNAPSHOT-lambda.jar
du -sh .
```

AWS Lambda zip package limits to keep in mind:

- Direct upload through Lambda API, SDK, or console: 50 MB zipped.
- Upload through S3: use this when the zipped package is larger than 50 MB.
- Unzipped deployment package contents: 250 MB.

## Lambda Runtime

Recommended runtime:

```text
Java 21
```

Handler:

```text
com.dailytools.s3migration.batchlambda.S3BatchDecryptCopyHandler::handleRequest
```

Timeout:

```text
Start with 1-5 minutes for small objects. Increase after testing real object sizes.
```

Memory:

```text
Start with 1024 MB or 2048 MB. Higher memory also gives Lambda more CPU and network.
```

Ephemeral storage:

```text
Set /tmp storage above your largest decrypted object size, plus headroom.
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
TARGET_KMS_KEY_ID=
TARGET_KEY_PREFIX=
TEMP_DIR=/tmp/s3-batch-decrypt
CRYPTO_MODE=ENCRYPTION_ONLY
CRYPTO_STORAGE_MODE=OBJECT_METADATA
RESULT_STRING_MAX_LENGTH=1024
COPY_METADATA_KEYS=
METADATA_COPY_DEBUG=false
```

Notes:

- `SOURCE_KMS_KEY_ID` is for the source client-side encrypted objects.
- `TARGET_BUCKET` is where decrypted objects are uploaded.
- `TARGET_KMS_KEY_ID` is optional. Set it only when the target bucket policy requires an explicit SSE-KMS key id in the upload request. When empty, target bucket default encryption is used. Prefer a KMS alias ARN if the target key may change.
- `TARGET_KEY_PREFIX` is optional. When empty, the original object key is preserved.
- `CRYPTO_MODE=ENCRYPTION_ONLY` matches legacy AWS SDK v1 client-side encryption mode.
- `COPY_METADATA_KEYS` is optional. When empty, no source object metadata is migrated.
- `METADATA_COPY_DEBUG=true` prints metadata key-level diagnostics without printing metadata values.

## Target SSE-KMS

By default, uploads do not set target SSE headers. If the target bucket has default SSE-KMS, S3 encrypts the new object with the bucket default key after authorization succeeds.

If the target bucket policy denies uploads unless the request includes `s3:x-amz-server-side-encryption-aws-kms-key-id`, set:

```text
TARGET_KMS_KEY_ID=arn:aws:kms:us-east-1:444455556666:alias/target-s3-key
```

When set, the Lambda sends explicit SSE-KMS headers on each target `PutObject`. The Lambda execution role also needs target key permissions:

```text
kms:GenerateDataKey
kms:Encrypt
```

If the target key is in another account, the target KMS key policy must also allow the Lambda execution role. If you later point the bucket default encryption to a different key, update `TARGET_KMS_KEY_ID` or use a stable alias ARN.

## Metadata Copy

By default, the Lambda does not migrate source object metadata. To copy selected metadata without extra S3 API calls, set `COPY_METADATA_KEYS` to a comma-separated allowlist:

```text
COPY_METADATA_KEYS=content-type,cache-control,x-amz-meta-owner
```

The Lambda uses the `ObjectMetadata` returned by the existing source `GetObject` call. It does not call `HeadObject` or `GetObjectMetadata` for this feature.

Supported system metadata keys:

```text
content-type
content-encoding
content-language
cache-control
content-disposition
expires
```

User metadata can be listed either with or without the S3 header prefix:

```text
COPY_METADATA_KEYS=x-amz-meta-owner,department
```

Legacy client-side encryption instruction metadata is never copied, even if listed. Examples include `x-amz-key`, `x-amz-iv`, and `x-amz-matdesc`.

This feature does not copy object tags, ACLs, ETag, LastModified, VersionId, storage class, or source SSE/KMS metadata.

For metadata troubleshooting on a small sample run, temporarily set:

```text
METADATA_COPY_DEBUG=true
```

CloudWatch will show one line per task with requested keys, source metadata keys, copied keys, skipped legacy encryption keys, and whether target metadata was attached. The log does not print metadata values.

## S3 Batch Operations

Create an S3 Batch Operations job with:

```text
Operation: Invoke AWS Lambda function
Lambda function: the deployed s3-lambda function ARN
Manifest: CSV or S3 Inventory manifest containing source bucket/key/version
Completion report: enabled
```

The Lambda receives one S3 Batch task, decrypts the source object locally, uploads it to `TARGET_BUCKET`, and returns one task result:

```text
Succeeded
TemporaryFailure
PermanentFailure
```

Transient S3/KMS/network failures are returned as `TemporaryFailure` so S3 Batch can retry them. Invalid input and unexpected validation failures are returned as `PermanentFailure`.

## Download Manifest CSV Files

The standalone Python script `scripts/download_manifest_csvs.py` downloads every CSV object in a JSON manifest `files` array. It does not depend on the Java project or Maven. Python 3 and `boto3` are the only runtime requirements.

```bash
python3 -m pip install boto3
python3 scripts/download_manifest_csvs.py \
  s3://manifest-bucket/path/manifest.json \
  --output-dir ./manifest-csvs
```

Add `--merge` to decompress any `.gz` parts and concatenate all downloaded CSV data into `manifest-csvs/merged.csv`:

```bash
python3 scripts/download_manifest_csvs.py \
  s3://manifest-bucket/path/manifest.json \
  --output-dir ./manifest-csvs \
  --merge
```

Use `--merged-output ./all.csv` to choose the merged file path. For a local manifest, the script uses `destinationBucket` when present; otherwise pass `--csv-bucket`. AWS credentials use the normal boto3 credential chain, with optional `--profile` and `--region` arguments.

## IAM

Lambda execution role needs:

```text
s3:GetObject
s3:GetObjectVersion
s3:PutObject
kms:Decrypt
```

If `TARGET_KMS_KEY_ID` is set, also allow the target KMS key:

```text
kms:GenerateDataKey
kms:Encrypt
```

S3 Batch Operations role needs:

```text
lambda:InvokeFunction
s3:GetObject on the manifest
s3:PutObject if writing completion reports
```

If source and target buckets are in different accounts, configure bucket policy and KMS key policy so the Lambda execution role can read source objects, decrypt source materials, and write target objects.

## Create Lambda With AWS CLI

Build the deployment jar first:

```bash
cd s3-lambda
mvn clean package
```

Set variables for your account and buckets:

```bash
export AWS_REGION=us-east-1
export LAMBDA_NAME=s3-batch-decrypt-copy
export ACCOUNT_ID=111122223333

export SOURCE_BUCKET=your-source-bucket
export TARGET_BUCKET=your-target-bucket
export SOURCE_KMS_KEY_ID=arn:aws:kms:us-east-1:111122223333:key/source-key-id
export TARGET_KMS_KEY_ID=arn:aws:kms:us-east-1:444455556666:alias/target-s3-key

export LAMBDA_ROLE_NAME=s3-batch-decrypt-copy-lambda-role
export LAMBDA_ROLE_ARN=arn:aws:iam::${ACCOUNT_ID}:role/${LAMBDA_ROLE_NAME}
```

Create the Lambda execution role:

```bash
cat > /tmp/lambda-trust-policy.json <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "lambda.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF

aws iam create-role \
  --role-name "$LAMBDA_ROLE_NAME" \
  --assume-role-policy-document file:///tmp/lambda-trust-policy.json
```

Attach the minimum inline policy:

```bash
cat > /tmp/lambda-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "CloudWatchLogs",
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "*"
    },
    {
      "Sid": "ReadSourceObjects",
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:GetObjectVersion"
      ],
      "Resource": "arn:aws:s3:::${SOURCE_BUCKET}/*"
    },
    {
      "Sid": "WriteTargetObjects",
      "Effect": "Allow",
      "Action": [
        "s3:PutObject"
      ],
      "Resource": "arn:aws:s3:::${TARGET_BUCKET}/*"
    },
    {
      "Sid": "DecryptSourceClientSideKms",
      "Effect": "Allow",
      "Action": [
        "kms:Decrypt"
      ],
      "Resource": "${SOURCE_KMS_KEY_ID}"
    }
  ]
}
EOF

aws iam put-role-policy \
  --role-name "$LAMBDA_ROLE_NAME" \
  --policy-name s3-batch-decrypt-copy-policy \
  --policy-document file:///tmp/lambda-policy.json
```

Wait a few seconds for IAM propagation:

```bash
sleep 10
```

Create the Lambda function:

```bash
aws lambda create-function \
  --function-name "$LAMBDA_NAME" \
  --runtime java21 \
  --role "$LAMBDA_ROLE_ARN" \
  --handler "com.dailytools.s3migration.batchlambda.S3BatchDecryptCopyHandler::handleRequest" \
  --zip-file fileb://target/s3-lambda-0.1.0-SNAPSHOT-lambda.jar \
  --timeout 300 \
  --memory-size 2048 \
  --ephemeral-storage '{"Size":1024}' \
  --environment "Variables={SOURCE_KMS_KEY_ID=${SOURCE_KMS_KEY_ID},TARGET_BUCKET=${TARGET_BUCKET},TARGET_KMS_KEY_ID=${TARGET_KMS_KEY_ID},SOURCE_REGION=${AWS_REGION},TARGET_REGION=${AWS_REGION},SOURCE_KMS_REGION=${AWS_REGION},CRYPTO_MODE=ENCRYPTION_ONLY,CRYPTO_STORAGE_MODE=OBJECT_METADATA,TEMP_DIR=/tmp/s3-batch-decrypt}"
```

If the target bucket uses default SSE-KMS instead of SSE-S3, also allow the Lambda role to use the target KMS key for writes:

```json
{
  "Effect": "Allow",
  "Action": [
    "kms:GenerateDataKey",
    "kms:Encrypt"
  ],
  "Resource": "arn:aws:kms:us-east-1:444455556666:key/target-key-id"
}
```

## Test Invoke

Create a single-task S3 Batch style event:

```bash
cat > /tmp/s3-batch-test-event.json <<EOF
{
  "invocationSchemaVersion": "1.0",
  "invocationId": "test-invoke-1",
  "job": {
    "id": "test-job-1"
  },
  "tasks": [
    {
      "taskId": "task-1",
      "s3BucketArn": "arn:aws:s3:::${SOURCE_BUCKET}",
      "s3Key": "path/to/test-object.txt",
      "s3VersionId": null
    }
  ]
}
EOF
```

Invoke the function:

```bash
aws lambda invoke \
  --function-name "$LAMBDA_NAME" \
  --payload fileb:///tmp/s3-batch-test-event.json \
  /tmp/s3-batch-test-response.json

cat /tmp/s3-batch-test-response.json
```

Expected success response shape:

```json
{
  "invocationSchemaVersion": "1.0",
  "invocationId": "test-invoke-1",
  "treatMissingKeysAs": "TemporaryFailure",
  "results": [
    {
      "taskId": "task-1",
      "resultCode": "Succeeded",
      "resultString": "Copied source-bucket/path/to/test-object.txt to target-bucket/path/to/test-object.txt"
    }
  ]
}
```

For keys with spaces or special characters, pass the S3 Batch encoded key format:

```json
{
  "s3Key": "path/to/a%20file%2Bname.txt"
}
```

## Update Existing Lambda

After code changes:

```bash
cd s3-lambda
mvn clean package

aws lambda update-function-code \
  --function-name "$LAMBDA_NAME" \
  --zip-file fileb://target/s3-lambda-0.1.0-SNAPSHOT-lambda.jar
```

Update environment variables if needed:

```bash
aws lambda update-function-configuration \
  --function-name "$LAMBDA_NAME" \
  --environment "Variables={SOURCE_KMS_KEY_ID=${SOURCE_KMS_KEY_ID},TARGET_BUCKET=${TARGET_BUCKET},TARGET_KMS_KEY_ID=${TARGET_KMS_KEY_ID},SOURCE_REGION=${AWS_REGION},TARGET_REGION=${AWS_REGION},SOURCE_KMS_REGION=${AWS_REGION},CRYPTO_MODE=ENCRYPTION_ONLY,CRYPTO_STORAGE_MODE=OBJECT_METADATA,TEMP_DIR=/tmp/s3-batch-decrypt}"
```
