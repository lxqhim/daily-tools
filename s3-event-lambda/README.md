# s3-event-lambda

Standalone Java Lambda for S3 Event Notifications delivered through SQS.

The handler receives an SQS event, parses each message body as an S3 event notification, decrypts the source object with the legacy AWS SDK v1 `AmazonS3Encryption` client, uploads the decrypted file to the target bucket, and returns an SQS partial batch response.

See [USAGE.md](USAGE.md) for build, deployment, SQS wiring, and test invoke commands.

## Handler

```text
com.dailytools.s3migration.eventlambda.S3EventSqsHandler::handleRequest
```
