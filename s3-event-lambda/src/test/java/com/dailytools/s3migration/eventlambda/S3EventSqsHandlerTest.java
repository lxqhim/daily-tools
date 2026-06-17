package com.dailytools.s3migration.eventlambda;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class S3EventSqsHandlerTest {

    private final S3EventSqsJsonCodec jsonCodec = new S3EventSqsJsonCodec();

    @Test
    void processesS3EventInsideSqsMessageAndReturnsNoBatchFailures() throws Exception {
        List<S3ObjectTask> processed = new ArrayList<>();
        S3EventSqsHandler handler = new S3EventSqsHandler(jsonCodec, processed::add);
        String event = sqsEvent("message-1", s3ObjectCreatedBody("source-bucket", "folder/a%20b%2Bc.txt", "version-1"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        handler.handleRequest(new ByteArrayInputStream(event.getBytes(StandardCharsets.UTF_8)), output, null);

        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("{\"batchItemFailures\":[]}");
        assertThat(processed).hasSize(1);
        S3ObjectTask task = processed.getFirst();
        assertThat(task.sourceBucket()).isEqualTo("source-bucket");
        assertThat(task.sourceKey()).isEqualTo("folder/a b+c.txt");
        assertThat(task.versionId()).isEqualTo("version-1");
    }

    @Test
    void returnsFailedMessageIdsForObjectProcessingFailures() throws Exception {
        S3EventSqsHandler handler = new S3EventSqsHandler(jsonCodec, task -> {
            throw new IllegalStateException("decrypt failed");
        });
        String event = sqsEvent("message-1", s3ObjectCreatedBody("source-bucket", "folder/file.txt", null));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        handler.handleRequest(new ByteArrayInputStream(event.getBytes(StandardCharsets.UTF_8)), output, null);

        assertThat(output.toString(StandardCharsets.UTF_8))
                .isEqualTo("{\"batchItemFailures\":[{\"itemIdentifier\":\"message-1\"}]}");
    }

    @Test
    void ignoresS3TestEventMessages() throws Exception {
        List<S3ObjectTask> processed = new ArrayList<>();
        S3EventSqsHandler handler = new S3EventSqsHandler(jsonCodec, processed::add);
        String event = sqsEvent("message-1", "{\"Service\":\"Amazon S3\",\"Event\":\"s3:TestEvent\"}");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        handler.handleRequest(new ByteArrayInputStream(event.getBytes(StandardCharsets.UTF_8)), output, null);

        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("{\"batchItemFailures\":[]}");
        assertThat(processed).isEmpty();
    }

    private static String sqsEvent(String messageId, String body) {
        return """
                {
                  "Records": [
                    {
                      "messageId": "%s",
                      "body": %s
                    }
                  ]
                }
                """
                .formatted(messageId, quote(body));
    }

    private static String s3ObjectCreatedBody(String bucket, String key, String versionId) {
        String versionField = versionId == null ? "" : ",\"versionId\":\"" + versionId + "\"";
        return """
                {
                  "Records": [
                    {
                      "eventName": "ObjectCreated:Put",
                      "s3": {
                        "bucket": {
                          "name": "%s"
                        },
                        "object": {
                          "key": "%s"%s
                        }
                      }
                    }
                  ]
                }
                """
                .formatted(bucket, key, versionField);
    }

    private static String quote(String value) {
        return "\""
                + value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t")
                + "\"";
    }
}
