# S3 Batch Operations Key Rename SOP

This procedure deploys and runs the key-rename Lambda in this repository for a standard
S3 general purpose bucket. It copies objects with S3 `CopyObject`; it does not decrypt,
download, or delete source objects.

For example, with the configuration below, only the third slash-delimited key segment is
renamed:

```text
HK/XXX/BAR/file.txt -> HK/XXX/BAR_PRINT/file.txt
```

Use the same AWS Region for the data bucket, Lambda function, and S3 Batch Operations job.
Do not configure an S3 Object Created event trigger: S3 Batch Operations invokes this
function directly and requires its Batch Operations request/response format.

## 1. Set deployment values

Replace each example value before running any command.

```bash
export ACCOUNT_ID=111122223333
export REGION=ap-east-1
export DATA_BUCKET=my-data-bucket
export MANIFEST_BUCKET=my-batch-manifest-bucket
export REPORT_BUCKET=my-batch-report-bucket

export LAMBDA_NAME=s3-batch-key-rename
export LAMBDA_ROLE_NAME=s3-batch-key-rename-lambda-role
export BATCH_ROLE_NAME=s3-batch-key-rename-batch-role
export LAMBDA_ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/${LAMBDA_ROLE_NAME}"
export BATCH_ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/${BATCH_ROLE_NAME}"
```

The Lambda environment variables are:

```text
RENAME_SOURCE_SEGMENT=BAR
RENAME_TARGET_SEGMENT=BAR_PRINT
RENAME_SOURCE_SEGMENT_INDEX=3
RESULT_STRING_MAX_LENGTH=1024
```

`RENAME_SOURCE_SEGMENT_INDEX` is one-based. Lambda replaces the configured source segment
only when it occurs at that precise slash-delimited key position. For example, at index 4,
`HK/BAR/XXX/BAR/file.txt` becomes `HK/BAR/XXX/BAR_PRINT/file.txt`; the `BAR` in the
second segment remains unchanged.

## 2. Create the Lambda execution role

This role is assumed by Lambda and performs the actual S3 copy. It is different from the
S3 Batch Operations job role.

Create `lambda-trust-policy.json`:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Service": "lambda.amazonaws.com"},
    "Action": "sts:AssumeRole"
  }]
}
```

Create the role:

```bash
aws iam create-role \
  --role-name "$LAMBDA_ROLE_NAME" \
  --assume-role-policy-document file://lambda-trust-policy.json
```

Create `lambda-rename-policy.json`. The tagging permissions allow a normal `CopyObject`
to copy object tags. Do not add `s3:DeleteObject`.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "WriteCloudWatchLogs",
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "*"
    },
    {
      "Sid": "ReadRenameSourceObjects",
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:GetObjectVersion",
        "s3:GetObjectTagging"
      ],
      "Resource": "arn:aws:s3:::my-data-bucket/*"
    },
    {
      "Sid": "WriteRenamedObjects",
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:PutObjectTagging"
      ],
      "Resource": "arn:aws:s3:::my-data-bucket/*"
    }
  ]
}
```

Replace `my-data-bucket` in the policy, then attach it:

```bash
aws iam put-role-policy \
  --role-name "$LAMBDA_ROLE_NAME" \
  --policy-name s3-batch-key-rename \
  --policy-document file://lambda-rename-policy.json
```

If copied objects use SSE-KMS, add the following statement to the execution role policy
and grant the same role access in the KMS key policy:

```json
{
  "Sid": "UseSseKmsKeyForCopy",
  "Effect": "Allow",
  "Action": ["kms:Decrypt", "kms:GenerateDataKey"],
  "Resource": "arn:aws:kms:ap-east-1:111122223333:key/your-kms-key-id"
}
```

## 3. Build and create the Lambda function

Build the shaded deployment jar:

```bash
mvn clean package
```

The artifact is `target/s3-lambda-0.1.0-SNAPSHOT-lambda.jar`.

Create the function:

```bash
aws lambda create-function \
  --function-name "$LAMBDA_NAME" \
  --runtime java21 \
  --role "$LAMBDA_ROLE_ARN" \
  --handler "com.dailytools.s3migration.batchlambda.S3BatchKeyRenameHandler::handleRequest" \
  --zip-file fileb://target/s3-lambda-0.1.0-SNAPSHOT-lambda.jar \
  --timeout 60 \
  --memory-size 512 \
  --environment "Variables={RENAME_SOURCE_SEGMENT=BAR,RENAME_TARGET_SEGMENT=BAR_PRINT,RENAME_SOURCE_SEGMENT_INDEX=3,RESULT_STRING_MAX_LENGTH=1024}" \
  --region "$REGION"
```

If the jar is larger than Lambda's direct-upload limit, upload it to an S3 bucket in the
same Region and create the function with `--code S3Bucket=...,S3Key=...` instead. Start
with a small reserved concurrency (for example, 5) and raise it after a test job succeeds.

Add and verify permission for S3 Batch Operations to invoke the function:

```bash
aws lambda add-permission \
  --function-name "$LAMBDA_NAME" \
  --statement-id AllowS3BatchOperationsInvoke \
  --action lambda:InvokeFunction \
  --principal s3.amazonaws.com \
  --source-account "$ACCOUNT_ID" \
  --region "$REGION"

aws lambda get-policy \
  --function-name "$LAMBDA_NAME" \
  --region "$REGION"
```

## 4. Create the S3 Batch Operations job role

This role lets S3 Batch Operations read the manifest, invoke Lambda, and write the
completion report. Lambda's execution role, not this role, copies the actual data objects.

Create `batch-trust-policy.json`:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Service": "batchoperations.s3.amazonaws.com"},
    "Action": "sts:AssumeRole"
  }]
}
```

Create the role:

```bash
aws iam create-role \
  --role-name "$BATCH_ROLE_NAME" \
  --assume-role-policy-document file://batch-trust-policy.json
```

Create `batch-job-policy.json` and replace all example ARNs:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ReadManifest",
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:GetObjectVersion"],
      "Resource": "arn:aws:s3:::my-batch-manifest-bucket/manifests/*"
    },
    {
      "Sid": "WriteCompletionReport",
      "Effect": "Allow",
      "Action": "s3:PutObject",
      "Resource": "arn:aws:s3:::my-batch-report-bucket/rename-reports/*"
    },
    {
      "Sid": "InvokeRenameLambda",
      "Effect": "Allow",
      "Action": "lambda:InvokeFunction",
      "Resource": "arn:aws:lambda:ap-east-1:111122223333:function:s3-batch-key-rename"
    }
  ]
}
```

Attach it:

```bash
aws iam put-role-policy \
  --role-name "$BATCH_ROLE_NAME" \
  --policy-name s3-batch-key-rename-job \
  --policy-document file://batch-job-policy.json
```

For an SSE-KMS encrypted S3 Inventory manifest, the job role also requires `kms:Decrypt`
and `kms:GenerateDataKey` on the manifest KMS key. For cross-account reports in ACL-enabled
buckets, it can also need `s3:PutObjectAcl`.

## 5. Create and upload a manifest

For a manual CSV manifest, use UTF-8 without a BOM and **do not include a header row**.
Every key must be URL encoded. Include either `Bucket,Key` for every row or
`Bucket,Key,VersionId` for every row; do not mix the formats.

Example `rename-bar.csv`:

```csv
my-data-bucket,HK%2Fcustomer-a%2FBAR%2Fone.pdf
my-data-bucket,HK%2Fcustomer-b%2FBAR%2Ftwo.pdf
```

Upload it:

```bash
aws s3 cp rename-bar.csv "s3://${MANIFEST_BUCKET}/manifests/rename-bar.csv" \
  --region "$REGION"
```

Record its ETag:

```bash
export MANIFEST_KEY=manifests/rename-bar.csv
export MANIFEST_ETAG=$(aws s3api head-object \
  --bucket "$MANIFEST_BUCKET" \
  --key "$MANIFEST_KEY" \
  --query ETag --output text \
  --region "$REGION" | tr -d '"')
```

For production, use S3 Inventory and Athena to generate a precise object list. A simple
prefix cannot accurately select a pattern such as `HK/*/BAR/*`. Exclude existing target
keys such as `BAR_PRINT` from the manifest.

## 6. Create the Batch Operations job

Create the job with a completion report for all tasks:

```bash
aws s3control create-job \
  --account-id "$ACCOUNT_ID" \
  --operation "{\"LambdaInvoke\":{\"FunctionArn\":\"arn:aws:lambda:${REGION}:${ACCOUNT_ID}:function:${LAMBDA_NAME}\"}}" \
  --manifest "{\"Spec\":{\"Format\":\"S3BatchOperations_CSV_20180820\",\"Fields\":[\"Bucket\",\"Key\"]},\"Location\":{\"ObjectArn\":\"arn:aws:s3:::${MANIFEST_BUCKET}/${MANIFEST_KEY}\",\"ETag\":\"${MANIFEST_ETAG}\"}}" \
  --report "{\"Bucket\":\"arn:aws:s3:::${REPORT_BUCKET}\",\"Format\":\"Report_CSV_20180820\",\"Enabled\":true,\"Prefix\":\"rename-reports/\",\"ReportScope\":\"AllTasks\"}" \
  --priority 10 \
  --role-arn "$BATCH_ROLE_ARN" \
  --description "Rename BAR to BAR_PRINT by Lambda" \
  --region "$REGION"
```

If VersionId is present in the manifest, change manifest `Fields` to
`["Bucket","Key","VersionId"]`. Keep the returned `JobId`, then confirm and monitor
the job in the S3 console or with `aws s3control describe-job`.

## 7. Verify before full rollout

1. Start with a manifest containing 1-10 non-production objects.
2. Verify the completion report: every intended task is `Succeeded`.
3. Confirm the target keys and object contents.
4. Confirm the original keys still exist.
5. Check CloudWatch Logs for any `AccessDenied`, KMS, or validation errors.
6. Only then generate and run the full manifest.

## Deployment principal permissions

The person or CI role performing this procedure normally needs `iam:CreateRole`,
`iam:PutRolePolicy`, `iam:PassRole`, `lambda:CreateFunction`, `lambda:AddPermission`,
`lambda:GetPolicy`, `s3:PutObject`, `s3:GetObject`, `s3:CreateJob`, `s3:DescribeJob`, and
`s3:UpdateJobStatus`. Generating an S3 object list in the console can additionally require
`s3:PutInventoryConfiguration`.

## Official AWS references

- [Invoke AWS Lambda function with S3 Batch Operations](https://docs.aws.amazon.com/AmazonS3/latest/userguide/batch-ops-invoke-lambda.html)
- [Create an S3 Batch Operations job](https://docs.aws.amazon.com/AmazonS3/latest/userguide/batch-ops-create-job.html)
- [S3 Batch Operations IAM role policies](https://docs.aws.amazon.com/AmazonS3/latest/userguide/batch-ops-iam-role-policies.html)
- [CopyObject permissions](https://docs.aws.amazon.com/AmazonS3/latest/API/API_CopyObject.html)
- [Deploy Java Lambda zip or JAR packages](https://docs.aws.amazon.com/lambda/latest/dg/java-package.html)
