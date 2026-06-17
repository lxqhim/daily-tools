package com.dailytools.s3migration.eventlambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class S3EventSqsHandler implements RequestStreamHandler {

    private final S3EventSqsJsonCodec jsonCodec;
    private final S3ObjectProcessor processor;

    public S3EventSqsHandler() {
        EventLambdaConfig config = EventLambdaConfig.fromEnvironment();
        this.jsonCodec = new S3EventSqsJsonCodec();
        this.processor = new LegacyKmsDecryptCopyProcessor(config);
    }

    S3EventSqsHandler(S3EventSqsJsonCodec jsonCodec, S3ObjectProcessor processor) {
        this.jsonCodec = jsonCodec;
        this.processor = processor;
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        List<SqsMessage> messages = jsonCodec.readSqsMessages(input);
        List<String> failedMessageIds = new ArrayList<>();
        for (SqsMessage message : messages) {
            try {
                List<S3ObjectTask> tasks = jsonCodec.readS3ObjectTasks(message.body());
                for (S3ObjectTask task : tasks) {
                    processor.process(task);
                }
            } catch (Exception exception) {
                failedMessageIds.add(message.messageId());
                logFailure(context, message.messageId(), exception);
            }
        }
        jsonCodec.writeBatchResponse(output, failedMessageIds);
    }

    private static void logFailure(Context context, String messageId, Exception exception) {
        if (context == null) {
            return;
        }
        LambdaLogger logger = context.getLogger();
        if (logger != null) {
            logger.log("Failed to process SQS message " + messageId + ": " + exception + "\n");
        }
    }
}
