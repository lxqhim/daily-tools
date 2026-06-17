package com.dailytools.s3migration.eventlambda;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class S3EventSqsJsonCodec {

    private static final String S3_TEST_EVENT = "s3:TestEvent";
    private static final String OBJECT_CREATED_PREFIX = "ObjectCreated:";

    private final JsonFactory jsonFactory = new JsonFactory();

    List<SqsMessage> readSqsMessages(InputStream input) throws IOException {
        List<SqsMessage> messages = new ArrayList<>();
        try (JsonParser parser = jsonFactory.createParser(input)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("SQS event must be a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if ("Records".equals(fieldName)) {
                    readSqsRecords(parser, valueToken, messages);
                } else {
                    parser.skipChildren();
                }
            }
        }
        return messages;
    }

    List<S3ObjectTask> readS3ObjectTasks(String body) throws IOException {
        List<S3ObjectTask> tasks = new ArrayList<>();
        if (body == null || body.isBlank()) {
            throw new IOException("SQS message body is required");
        }
        try (JsonParser parser = jsonFactory.createParser(new StringReader(body))) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("S3 event body must be a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if ("Event".equals(fieldName) && S3_TEST_EVENT.equals(readString(parser, valueToken))) {
                    parser.skipChildren();
                    return List.of();
                } else if ("Records".equals(fieldName)) {
                    readS3Records(parser, valueToken, tasks);
                } else {
                    parser.skipChildren();
                }
            }
        }
        return tasks;
    }

    void writeBatchResponse(OutputStream output, List<String> failedMessageIds) throws IOException {
        try (JsonGenerator generator = jsonFactory.createGenerator(output)) {
            generator.writeStartObject();
            generator.writeArrayFieldStart("batchItemFailures");
            for (String failedMessageId : failedMessageIds) {
                generator.writeStartObject();
                generator.writeStringField("itemIdentifier", failedMessageId);
                generator.writeEndObject();
            }
            generator.writeEndArray();
            generator.writeEndObject();
        }
    }

    private void readSqsRecords(JsonParser parser, JsonToken token, List<SqsMessage> messages) throws IOException {
        if (token != JsonToken.START_ARRAY) {
            throw new IOException("SQS event Records must be a JSON array");
        }
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            messages.add(readSqsRecord(parser));
        }
    }

    private SqsMessage readSqsRecord(JsonParser parser) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw new IOException("SQS record must be a JSON object");
        }
        String messageId = null;
        String body = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String fieldName = parser.currentName();
            JsonToken valueToken = parser.nextToken();
            if ("messageId".equals(fieldName)) {
                messageId = readString(parser, valueToken);
            } else if ("body".equals(fieldName)) {
                body = readString(parser, valueToken);
            } else {
                parser.skipChildren();
            }
        }
        if (messageId == null || messageId.isBlank()) {
            throw new IOException("SQS record messageId is required");
        }
        return new SqsMessage(messageId, body);
    }

    private void readS3Records(JsonParser parser, JsonToken token, List<S3ObjectTask> tasks) throws IOException {
        if (token != JsonToken.START_ARRAY) {
            throw new IOException("S3 event Records must be a JSON array");
        }
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            S3ObjectTask task = readS3Record(parser);
            if (task != null) {
                tasks.add(task);
            }
        }
    }

    private S3ObjectTask readS3Record(JsonParser parser) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw new IOException("S3 record must be a JSON object");
        }
        String eventName = null;
        String bucketName = null;
        String encodedKey = null;
        String versionId = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String fieldName = parser.currentName();
            JsonToken valueToken = parser.nextToken();
            if ("eventName".equals(fieldName)) {
                eventName = readString(parser, valueToken);
            } else if ("s3".equals(fieldName)) {
                S3ObjectFields fields = readS3Fields(parser, valueToken);
                bucketName = fields.bucketName();
                encodedKey = fields.encodedKey();
                versionId = fields.versionId();
            } else {
                parser.skipChildren();
            }
        }
        if (eventName == null || !eventName.startsWith(OBJECT_CREATED_PREFIX)) {
            return null;
        }
        if (bucketName == null || bucketName.isBlank()) {
            throw new IOException("S3 event bucket name is required");
        }
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IOException("S3 event object key is required");
        }
        return new S3ObjectTask(bucketName, decodeS3EventKey(encodedKey), versionId);
    }

    private S3ObjectFields readS3Fields(JsonParser parser, JsonToken token) throws IOException {
        if (token != JsonToken.START_OBJECT) {
            throw new IOException("S3 field must be a JSON object");
        }
        String bucketName = null;
        String encodedKey = null;
        String versionId = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String fieldName = parser.currentName();
            JsonToken valueToken = parser.nextToken();
            if ("bucket".equals(fieldName)) {
                bucketName = readS3BucketName(parser, valueToken);
            } else if ("object".equals(fieldName)) {
                S3ObjectFields objectFields = readS3ObjectFields(parser, valueToken);
                encodedKey = objectFields.encodedKey();
                versionId = objectFields.versionId();
            } else {
                parser.skipChildren();
            }
        }
        return new S3ObjectFields(bucketName, encodedKey, versionId);
    }

    private String readS3BucketName(JsonParser parser, JsonToken token) throws IOException {
        if (token != JsonToken.START_OBJECT) {
            throw new IOException("S3 bucket field must be a JSON object");
        }
        String bucketName = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String fieldName = parser.currentName();
            JsonToken valueToken = parser.nextToken();
            if ("name".equals(fieldName)) {
                bucketName = readString(parser, valueToken);
            } else {
                parser.skipChildren();
            }
        }
        return bucketName;
    }

    private S3ObjectFields readS3ObjectFields(JsonParser parser, JsonToken token) throws IOException {
        if (token != JsonToken.START_OBJECT) {
            throw new IOException("S3 object field must be a JSON object");
        }
        String encodedKey = null;
        String versionId = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String fieldName = parser.currentName();
            JsonToken valueToken = parser.nextToken();
            if ("key".equals(fieldName)) {
                encodedKey = readString(parser, valueToken);
            } else if ("versionId".equals(fieldName)) {
                versionId = readString(parser, valueToken);
            } else {
                parser.skipChildren();
            }
        }
        return new S3ObjectFields(null, encodedKey, versionId);
    }

    private static String readString(JsonParser parser, JsonToken token) throws IOException {
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token != JsonToken.VALUE_STRING) {
            return parser.getValueAsString();
        }
        return parser.getText();
    }

    static String decodeS3EventKey(String encodedKey) {
        return URLDecoder.decode(encodedKey, StandardCharsets.UTF_8);
    }

    private record S3ObjectFields(String bucketName, String encodedKey, String versionId) {}
}
