package com.dailytools.s3migration.batchlambda;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class S3BatchDecryptCopyHandlerTest {

    @Test
    void returnsS3BatchResponseForEachTask() throws Exception {
        S3BatchDecryptCopyHandler handler = new S3BatchDecryptCopyHandler(
                new S3BatchJsonCodec(),
                task -> new S3BatchTaskResult(
                        task.getTaskId(), BatchResultCode.SUCCEEDED, "processed " + task.getS3Key()));
        String event = """
                {
                  "invocationSchemaVersion": "1.0",
                  "invocationId": "invoke-1",
                  "job": {"id": "job-1"},
                  "tasks": [
                    {
                      "taskId": "task-1",
                      "s3BucketArn": "arn:aws:s3:::source-bucket",
                      "s3Key": "folder/a%20b.txt",
                      "s3VersionId": "version-1"
                    }
                  ]
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        handler.handleRequest(new ByteArrayInputStream(event.getBytes(StandardCharsets.UTF_8)), output, null);

        assertThat(output.toString(StandardCharsets.UTF_8))
                .isEqualTo(
                        "{\"invocationSchemaVersion\":\"1.0\",\"invocationId\":\"invoke-1\","
                                + "\"treatMissingKeysAs\":\"TemporaryFailure\",\"results\":[{\"taskId\":\"task-1\","
                                + "\"resultCode\":\"Succeeded\",\"resultString\":\"processed folder/a%20b.txt\"}]}");
    }
}
