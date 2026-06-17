package com.dailytools.s3migration.batchlambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class S3BatchDecryptCopyHandler implements RequestStreamHandler {

    private static final String DEFAULT_SCHEMA_VERSION = "1.0";

    private final S3BatchJsonCodec jsonCodec;
    private final BatchTaskProcessor processor;

    public S3BatchDecryptCopyHandler() {
        BatchLambdaConfig config = BatchLambdaConfig.fromEnvironment();
        this.jsonCodec = new S3BatchJsonCodec();
        this.processor = new LegacyKmsDecryptCopyProcessor(config);
    }

    S3BatchDecryptCopyHandler(S3BatchJsonCodec jsonCodec, BatchTaskProcessor processor) {
        this.jsonCodec = jsonCodec;
        this.processor = processor;
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        S3BatchLambdaEvent event = jsonCodec.readEvent(input);
        S3BatchLambdaResponse response = handle(event);
        jsonCodec.writeResponse(output, response);
    }

    S3BatchLambdaResponse handle(S3BatchLambdaEvent event) {
        String schemaVersion = hasValue(event.getInvocationSchemaVersion())
                ? event.getInvocationSchemaVersion()
                : DEFAULT_SCHEMA_VERSION;
        List<S3BatchTaskResult> results = new ArrayList<>();
        for (S3BatchTask task : event.getTasks()) {
            results.add(processor.process(task));
        }
        return new S3BatchLambdaResponse(schemaVersion, event.getInvocationId(), results);
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }
}
