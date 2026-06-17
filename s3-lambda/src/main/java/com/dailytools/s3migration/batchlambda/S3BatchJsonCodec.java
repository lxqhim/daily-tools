package com.dailytools.s3migration.batchlambda;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

final class S3BatchJsonCodec {

    private final JsonFactory jsonFactory = new JsonFactory();

    S3BatchLambdaEvent readEvent(InputStream input) throws IOException {
        S3BatchLambdaEvent event = new S3BatchLambdaEvent();
        try (JsonParser parser = jsonFactory.createParser(input)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("S3 Batch event must be a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if ("invocationSchemaVersion".equals(fieldName)) {
                    event.setInvocationSchemaVersion(readString(parser, valueToken));
                } else if ("invocationId".equals(fieldName)) {
                    event.setInvocationId(readString(parser, valueToken));
                } else if ("tasks".equals(fieldName)) {
                    event.setTasks(readTasks(parser, valueToken));
                } else {
                    parser.skipChildren();
                }
            }
        }
        return event;
    }

    void writeResponse(OutputStream output, S3BatchLambdaResponse response) throws IOException {
        try (JsonGenerator generator = jsonFactory.createGenerator(output)) {
            generator.writeStartObject();
            generator.writeStringField("invocationSchemaVersion", response.getInvocationSchemaVersion());
            generator.writeStringField("invocationId", response.getInvocationId());
            generator.writeStringField("treatMissingKeysAs", response.getTreatMissingKeysAs());
            generator.writeArrayFieldStart("results");
            for (S3BatchTaskResult result : response.getResults()) {
                generator.writeStartObject();
                generator.writeStringField("taskId", result.getTaskId());
                generator.writeStringField("resultCode", result.getResultCode());
                writeNullableString(generator, "resultString", result.getResultString());
                generator.writeEndObject();
            }
            generator.writeEndArray();
            generator.writeEndObject();
        }
    }

    private List<S3BatchTask> readTasks(JsonParser parser, JsonToken token) throws IOException {
        List<S3BatchTask> tasks = new ArrayList<>();
        if (token == JsonToken.VALUE_NULL) {
            return tasks;
        }
        if (token != JsonToken.START_ARRAY) {
            throw new IOException("S3 Batch event tasks must be a JSON array");
        }
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            tasks.add(readTask(parser));
        }
        return tasks;
    }

    private S3BatchTask readTask(JsonParser parser) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw new IOException("S3 Batch task must be a JSON object");
        }
        S3BatchTask task = new S3BatchTask();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String fieldName = parser.currentName();
            JsonToken valueToken = parser.nextToken();
            if ("taskId".equals(fieldName)) {
                task.setTaskId(readString(parser, valueToken));
            } else if ("s3BucketArn".equals(fieldName)) {
                task.setS3BucketArn(readString(parser, valueToken));
            } else if ("s3Key".equals(fieldName)) {
                task.setS3Key(readString(parser, valueToken));
            } else if ("s3VersionId".equals(fieldName)) {
                task.setS3VersionId(readString(parser, valueToken));
            } else {
                parser.skipChildren();
            }
        }
        return task;
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

    private static void writeNullableString(JsonGenerator generator, String fieldName, String value) throws IOException {
        if (value == null) {
            generator.writeNullField(fieldName);
        } else {
            generator.writeStringField(fieldName, value);
        }
    }
}
