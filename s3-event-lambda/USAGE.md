# Usage

## Build And Test

```bash
cd s3-event-lambda
mvn test
mvn package
```

The Lambda deployment artifact is:

```text
target/s3-event-lambda-0.1.0-SNAPSHOT-lambda.jar
```

Check package size:

```bash
ls -lh target/s3-event-lambda-0.1.0-SNAPSHOT-lambda.jar
unzip -l target/s3-event-lambda-0.1.0-SNAPSHOT-lambda.jar | tail -n 3
```

## Lambda Runtime

Recommended runtime:

```text
Java 21
```

Handler:

```text
com.dailytools.s3migration.eventlambda.S3EventSqsHandler::handleRequest
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
TARGET_KEY_PREFIX=
TEMP_DIR=/tmp/s3-event-decrypt
CRYPTO_MODE=ENCRYPTION_ONLY
CRYPTO_STORAGE_MODE=OBJECT_METADATA
```

Notes:

- `SOURCE_KMS_KEY_ID` is for the source client-side encrypted objects.
- `TARGET_BUCKET` is where decrypted objects are uploaded.
- `TARGET_KEY_PREFIX` is optional. When empty, the original object key is preserved.
- `CRYPTO_MODE=ENCRYPTION_ONLY` matches legacy AWS SDK v1 client-side encryption mode.
- Non-`ObjectCreated:*` S3 events are ignored.
- S3 `s3:TestEvent` messages are acknowledged and ignored.

## Create Lambda And SQS With AWS CLI

Build the deployment jar:

```bash
cd s3-event-lambda
mvn clean package
```

Set variables:

```bash
export AWS_REGION=us-east-1
export ACCOUNT_ID=111122223333

export SOURCE_BUCKET=your-source-bucket
export TARGET_BUCKET=your-target-bucket
export SOURCE_KMS_KEY_ID=arn:aws:kms:us-east-1:111122223333:key/source-key-id

export QUEUE_NAME=s3-event-decrypt-copy-queue
export LAMBDA_NAME=s3-event-decrypt-copy
export LAMBDA_ROLE_NAME=s3-event-decrypt-copy-lambda-role
export LAMBDA_ROLE_ARN=arn:aws:iam::${ACCOUNT_ID}:role/${LAMBDA_ROLE_NAME}
```

Create the SQS queue:

```bash
aws sqs create-queue \
  --queue-name "$QUEUE_NAME" \
  --attributes VisibilityTimeout=900

export QUEUE_URL=$(aws sqs get-queue-url \
  --queue-name "$QUEUE_NAME" \
  --query QueueUrl \
  --output text)

export QUEUE_ARN=$(aws sqs get-queue-attributes \
  --queue-url "$QUEUE_URL" \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' \
  --output text)
```

Allow S3 to send events to the SQS queue:

```bash
cat > /tmp/s3-event-queue-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowS3SendMessage",
      "Effect": "Allow",
      "Principal": {
        "Service": "s3.amazonaws.com"
      },
      "Action": "sqs:SendMessage",
      "Resource": "${QUEUE_ARN}",
      "Condition": {
        "ArnEquals": {
          "aws:SourceArn": "arn:aws:s3:::${SOURCE_BUCKET}"
        },
        "StringEquals": {
          "aws:SourceAccount": "${ACCOUNT_ID}"
        }
      }
    }
  ]
}
EOF

aws sqs set-queue-attributes \
  --queue-url "$QUEUE_URL" \
  --attributes Policy="$(cat /tmp/s3-event-queue-policy.json)"
```

Configure S3 event notification for object-created events:

```bash
cat > /tmp/s3-notification.json <<EOF
{
  "QueueConfigurations": [
    {
      "Id": "s3-event-decrypt-copy",
      "QueueArn": "${QUEUE_ARN}",
      "Events": [
        "s3:ObjectCreated:*"
      ]
    }
  ]
}
EOF

aws s3api put-bucket-notification-configuration \
  --bucket "$SOURCE_BUCKET" \
  --notification-configuration file:///tmp/s3-notification.json
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
      "Sid": "PollSqs",
      "Effect": "Allow",
      "Action": [
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:GetQueueAttributes",
        "sqs:ChangeMessageVisibility"
      ],
      "Resource": "${QUEUE_ARN}"
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
  --policy-name s3-event-decrypt-copy-policy \
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
  --handler "com.dailytools.s3migration.eventlambda.S3EventSqsHandler::handleRequest" \
  --zip-file fileb://target/s3-event-lambda-0.1.0-SNAPSHOT-lambda.jar \
  --timeout 300 \
  --memory-size 2048 \
  --ephemeral-storage '{"Size":1024}' \
  --environment "Variables={SOURCE_KMS_KEY_ID=${SOURCE_KMS_KEY_ID},TARGET_BUCKET=${TARGET_BUCKET},SOURCE_REGION=${AWS_REGION},TARGET_REGION=${AWS_REGION},SOURCE_KMS_REGION=${AWS_REGION},CRYPTO_MODE=ENCRYPTION_ONLY,CRYPTO_STORAGE_MODE=OBJECT_METADATA,TEMP_DIR=/tmp/s3-event-decrypt}"
```

Create the SQS event source mapping. `ReportBatchItemFailures` is required so only failed SQS messages are retried:

```bash
aws lambda create-event-source-mapping \
  --function-name "$LAMBDA_NAME" \
  --event-source-arn "$QUEUE_ARN" \
  --batch-size 10 \
  --maximum-batching-window-in-seconds 5 \
  --function-response-types ReportBatchItemFailures
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

You can invoke the Lambda directly with an SQS-shaped event:

```bash
cat > /tmp/s3-event-sqs-test.json <<'EOF'
{
  "Records": [
    {
      "messageId": "message-1",
      "body": "{\"Records\":[{\"eventName\":\"ObjectCreated:Put\",\"s3\":{\"bucket\":{\"name\":\"your-source-bucket\"},\"object\":{\"key\":\"path/to/test-object.txt\"}}}]}"
    }
  ]
}
EOF

aws lambda invoke \
  --function-name "$LAMBDA_NAME" \
  --payload fileb:///tmp/s3-event-sqs-test.json \
  /tmp/s3-event-sqs-response.json

cat /tmp/s3-event-sqs-response.json
```

Expected success response:

```json
{
  "batchItemFailures": []
}
```

For keys with spaces or special characters, S3 event notifications use URL encoding:

```json
{
  "key": "path/to/a%20file%2Bname.txt"
}
```

## Update Existing Lambda

After code changes:

```bash
cd s3-event-lambda
mvn clean package

aws lambda update-function-code \
  --function-name "$LAMBDA_NAME" \
  --zip-file fileb://target/s3-event-lambda-0.1.0-SNAPSHOT-lambda.jar
```

Update environment variables if needed:

```bash
aws lambda update-function-configuration \
  --function-name "$LAMBDA_NAME" \
  --environment "Variables={SOURCE_KMS_KEY_ID=${SOURCE_KMS_KEY_ID},TARGET_BUCKET=${TARGET_BUCKET},SOURCE_REGION=${AWS_REGION},TARGET_REGION=${AWS_REGION},SOURCE_KMS_REGION=${AWS_REGION},CRYPTO_MODE=ENCRYPTION_ONLY,CRYPTO_STORAGE_MODE=OBJECT_METADATA,TEMP_DIR=/tmp/s3-event-decrypt}"
```
