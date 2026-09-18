package com.dailytools.s3migration.batchlambda;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.CopyObjectRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Copies {@code root/placeholder/source/...} to {@code root/placeholder/target/...}. */
final class S3KeyRenameProcessor implements BatchTaskProcessor {

    private final AmazonS3 s3Client;
    private final KeyRenameConfig config;

    S3KeyRenameProcessor(KeyRenameConfig config) {
        this(AmazonS3ClientBuilder.standard().withRegion(config.region()).build(), config);
    }

    S3KeyRenameProcessor(AmazonS3 s3Client, KeyRenameConfig config) {
        this.s3Client = s3Client;
        this.config = config;
    }

    @Override
    public S3BatchTaskResult process(S3BatchTask task) {
        try {
            CopyResult result = copyToRenamedKey(task);
            return result(task.getTaskId(), BatchResultCode.SUCCEEDED, result.message());
        } catch (NonMatchingKeyException exception) {
            return result(task.getTaskId(), BatchResultCode.SUCCEEDED, "Skipped: " + exception.getMessage());
        } catch (Exception exception) {
            BatchResultCode resultCode = isTemporary(exception)
                    ? BatchResultCode.TEMPORARY_FAILURE
                    : BatchResultCode.PERMANENT_FAILURE;
            return result(task == null ? null : task.getTaskId(), resultCode, exceptionSummary(exception));
        }
    }

    private CopyResult copyToRenamedKey(S3BatchTask task) {
        validateTask(task);
        String bucket = bucketNameFromArn(task.getS3BucketArn());
        String sourceKey = decodeS3Key(task.getS3Key());
        String targetKey = targetKey(sourceKey);
        CopyObjectRequest request = new CopyObjectRequest(bucket, sourceKey, bucket, targetKey);
        if (hasValue(task.getS3VersionId())) {
            request.withSourceVersionId(task.getS3VersionId());
        }
        s3Client.copyObject(request);
        return new CopyResult("Copied " + bucket + "/" + sourceKey + " to " + bucket + "/" + targetKey);
    }

    String targetKey(String sourceKey) {
        String[] segments = sourceKey.split("/", -1);
        int sourceSegmentArrayIndex = config.sourceSegmentIndex() - 1;
        if (segments.length <= sourceSegmentArrayIndex || !config.sourceSegment().equals(segments[sourceSegmentArrayIndex])) {
            throw new NonMatchingKeyException("Key does not match expected pattern "
                    + "with " + config.sourceSegment() + " at segment " + config.sourceSegmentIndex() + ": " + sourceKey);
        }
        segments[sourceSegmentArrayIndex] = config.targetSegment();
        return String.join("/", segments);
    }

    private S3BatchTaskResult result(String taskId, BatchResultCode resultCode, String resultString) {
        return new S3BatchTaskResult(taskId, resultCode, truncate(resultString, config.resultStringMaxLength()));
    }

    private static void validateTask(S3BatchTask task) {
        if (task == null) {
            throw new IllegalArgumentException("Task is required");
        }
        if (!hasValue(task.getTaskId())) {
            throw new IllegalArgumentException("Task id is required");
        }
        if (!hasValue(task.getS3BucketArn())) {
            throw new IllegalArgumentException("Task S3 bucket ARN is required");
        }
        if (!hasValue(task.getS3Key())) {
            throw new IllegalArgumentException("Task S3 key is required");
        }
    }

    private static String bucketNameFromArn(String bucketArn) {
        if (!bucketArn.startsWith("arn:")) {
            return bucketArn;
        }
        int marker = bucketArn.indexOf(":::");
        if (marker < 0 || marker + 3 >= bucketArn.length()) {
            throw new IllegalArgumentException("Unsupported S3 bucket ARN: " + bucketArn);
        }
        return bucketArn.substring(marker + 3);
    }

    private static String decodeS3Key(String encodedKey) {
        return URLDecoder.decode(encodedKey, StandardCharsets.UTF_8);
    }

    private static boolean isTemporary(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AmazonServiceException serviceException && isTemporaryAwsError(serviceException)) {
                return true;
            }
            if (current instanceof SdkClientException || current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isTemporaryAwsError(AmazonServiceException exception) {
        int statusCode = exception.getStatusCode();
        if (statusCode == 429 || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504) {
            return true;
        }
        String errorCode = exception.getErrorCode();
        if (errorCode == null) {
            return false;
        }
        String normalized = errorCode.toLowerCase(Locale.ROOT);
        return normalized.contains("throttl")
                || normalized.contains("slowdown")
                || normalized.contains("timeout")
                || normalized.contains("requestlimit")
                || normalized.contains("provisionedthroughput");
    }

    private static String exceptionSummary(Throwable throwable) {
        StringBuilder summary = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (!summary.isEmpty()) {
                summary.append(" | caused by: ");
            }
            summary.append(current.getClass().getSimpleName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                summary.append(": ").append(current.getMessage());
            }
            current = current.getCause();
        }
        return summary.toString();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank() && !"null".equalsIgnoreCase(value);
    }

    private record CopyResult(String message) {}

    private static final class NonMatchingKeyException extends RuntimeException {
        private NonMatchingKeyException(String message) {
            super(message);
        }
    }
}
