package com.dailytools.s3migration.batchlambda;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.SdkClientException;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.AmazonS3Encryption;
import com.amazonaws.services.s3.AmazonS3EncryptionClientBuilder;
import com.amazonaws.services.s3.model.CryptoConfiguration;
import com.amazonaws.services.s3.model.CryptoMode;
import com.amazonaws.services.s3.model.CryptoStorageMode;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.KMSEncryptionMaterialsProvider;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

final class LegacyKmsDecryptCopyProcessor implements BatchTaskProcessor {

    private static final String BC_PROVIDER = "BC";

    private final AmazonS3Encryption sourceClient;
    private final AmazonS3 targetClient;
    private final BatchLambdaConfig config;

    LegacyKmsDecryptCopyProcessor(BatchLambdaConfig config) {
        this(createSourceClient(config), createTargetClient(config), config);
    }

    LegacyKmsDecryptCopyProcessor(AmazonS3Encryption sourceClient, AmazonS3 targetClient, BatchLambdaConfig config) {
        this.sourceClient = sourceClient;
        this.targetClient = targetClient;
        this.config = config;
        createTempDirectory(config.tempDir());
    }

    @Override
    public S3BatchTaskResult process(S3BatchTask task) {
        try {
            CopyResult result = decryptAndCopy(task);
            return result(task.getTaskId(), BatchResultCode.SUCCEEDED, result.message());
        } catch (Exception exception) {
            BatchResultCode resultCode = isTemporary(exception)
                    ? BatchResultCode.TEMPORARY_FAILURE
                    : BatchResultCode.PERMANENT_FAILURE;
            return result(task.getTaskId(), resultCode, exceptionSummary(exception));
        }
    }

    private CopyResult decryptAndCopy(S3BatchTask task) throws IOException {
        validateTask(task);
        String sourceBucket = bucketNameFromArn(task.getS3BucketArn());
        String sourceKey = decodeS3Key(task.getS3Key());
        String targetKey = targetKey(sourceKey);
        Path localFile = tempFilePath();
        try {
            GetObjectRequest getObjectRequest = new GetObjectRequest(sourceBucket, sourceKey);
            if (hasValue(task.getS3VersionId())) {
                getObjectRequest.withVersionId(task.getS3VersionId());
            }
            ObjectMetadata sourceMetadata = sourceClient.getObject(getObjectRequest, localFile.toFile());
            if (!Files.exists(localFile)) {
                throw new IOException("Decrypt output file was not created: " + localFile);
            }
            PutObjectRequest putObjectRequest = new PutObjectRequest(config.targetBucket(), targetKey, localFile.toFile());
            ObjectMetadata targetMetadata = copiedMetadata(sourceMetadata);
            if (targetMetadata != null) {
                putObjectRequest.withMetadata(targetMetadata);
            }
            targetClient.putObject(putObjectRequest);
            return new CopyResult("Copied " + sourceBucket + "/" + sourceKey + " to " + config.targetBucket() + "/"
                    + targetKey);
        } finally {
            cleanup(localFile);
        }
    }

    private ObjectMetadata copiedMetadata(ObjectMetadata sourceMetadata) {
        Set<String> keys = config.copyMetadataKeys();
        if (sourceMetadata == null || keys.isEmpty()) {
            return null;
        }
        ObjectMetadata targetMetadata = new ObjectMetadata();
        boolean copiedSystemMetadata = copySystemMetadata(sourceMetadata, targetMetadata, keys);
        Map<String, String> userMetadata = copiedUserMetadata(sourceMetadata, keys);
        if (!userMetadata.isEmpty()) {
            targetMetadata.setUserMetadata(userMetadata);
        }
        if (!copiedSystemMetadata && userMetadata.isEmpty()) {
            return null;
        }
        return targetMetadata;
    }

    private static boolean copySystemMetadata(
            ObjectMetadata sourceMetadata, ObjectMetadata targetMetadata, Set<String> keys) {
        boolean copied = false;
        if (keys.contains("content-type") && hasValue(sourceMetadata.getContentType())) {
            targetMetadata.setContentType(sourceMetadata.getContentType());
            copied = true;
        }
        if (keys.contains("content-encoding") && hasValue(sourceMetadata.getContentEncoding())) {
            targetMetadata.setContentEncoding(sourceMetadata.getContentEncoding());
            copied = true;
        }
        if (keys.contains("content-language") && hasValue(sourceMetadata.getContentLanguage())) {
            targetMetadata.setContentLanguage(sourceMetadata.getContentLanguage());
            copied = true;
        }
        if (keys.contains("cache-control") && hasValue(sourceMetadata.getCacheControl())) {
            targetMetadata.setCacheControl(sourceMetadata.getCacheControl());
            copied = true;
        }
        if (keys.contains("content-disposition") && hasValue(sourceMetadata.getContentDisposition())) {
            targetMetadata.setContentDisposition(sourceMetadata.getContentDisposition());
            copied = true;
        }
        Date expires = sourceMetadata.getHttpExpiresDate();
        if (keys.contains("expires") && expires != null) {
            targetMetadata.setHttpExpiresDate(expires);
            copied = true;
        }
        return copied;
    }

    private static Map<String, String> copiedUserMetadata(ObjectMetadata sourceMetadata, Set<String> keys) {
        Map<String, String> copied = new LinkedHashMap<>();
        Map<String, String> sourceUserMetadata = sourceMetadata.getUserMetadata();
        if (sourceUserMetadata == null || sourceUserMetadata.isEmpty()) {
            return copied;
        }
        for (Map.Entry<String, String> entry : sourceUserMetadata.entrySet()) {
            String normalizedKey = BatchLambdaConfig.normalizeMetadataKey(entry.getKey());
            if (isLegacyClientSideEncryptionMetadata(normalizedKey)) {
                continue;
            }
            if (keys.contains(normalizedKey) || keys.contains("x-amz-meta-" + normalizedKey)) {
                copied.put(entry.getKey(), entry.getValue());
            }
        }
        return copied;
    }

    private static boolean isLegacyClientSideEncryptionMetadata(String normalizedKey) {
        return normalizedKey.equals("x-amz-key")
                || normalizedKey.equals("x-amz-iv")
                || normalizedKey.equals("x-amz-matdesc")
                || normalizedKey.equals("x-amz-cek-alg")
                || normalizedKey.equals("x-amz-wrap-alg")
                || normalizedKey.equals("x-amz-tag-len")
                || normalizedKey.equals("x-amz-unencrypted-content-length")
                || normalizedKey.equals("x-amz-crypto-instr-file");
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

    static String bucketNameFromArn(String bucketArn) {
        if (!hasValue(bucketArn)) {
            throw new IllegalArgumentException("S3 bucket ARN is required");
        }
        if (!bucketArn.startsWith("arn:")) {
            return bucketArn;
        }
        int marker = bucketArn.indexOf(":::");
        if (marker < 0 || marker + 3 >= bucketArn.length()) {
            throw new IllegalArgumentException("Unsupported S3 bucket ARN: " + bucketArn);
        }
        return bucketArn.substring(marker + 3);
    }

    static String decodeS3Key(String encodedKey) {
        return URLDecoder.decode(encodedKey, StandardCharsets.UTF_8);
    }

    private String targetKey(String sourceKey) {
        String prefix = config.targetKeyPrefix();
        if (prefix == null || prefix.isBlank()) {
            return sourceKey;
        }
        String normalizedPrefix = stripTrailingSlash(prefix);
        String normalizedKey = stripLeadingSlash(sourceKey);
        return normalizedPrefix + "/" + normalizedKey;
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String stripLeadingSlash(String value) {
        String result = value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result;
    }

    private Path tempFilePath() {
        return config.tempDir().resolve("s3-batch-decrypt-" + UUID.randomUUID() + ".bin");
    }

    private static AmazonS3Encryption createSourceClient(BatchLambdaConfig config) {
        ensureBouncyCastleProvider();
        return AmazonS3EncryptionClientBuilder.standard()
                .withRegion(config.sourceRegion())
                .withCryptoConfiguration(cryptoConfiguration(config))
                .withEncryptionMaterials(new KMSEncryptionMaterialsProvider(config.sourceKmsKeyId()))
                .build();
    }

    private static AmazonS3 createTargetClient(BatchLambdaConfig config) {
        return AmazonS3ClientBuilder.standard().withRegion(config.targetRegion()).build();
    }

    private static CryptoConfiguration cryptoConfiguration(BatchLambdaConfig config) {
        CryptoConfiguration configuration = new CryptoConfiguration()
                .withCryptoMode(cryptoMode(config.cryptoMode()))
                .withStorageMode(storageMode(config.cryptoStorageMode()));
        configuration.withAwsKmsRegion(Region.getRegion(Regions.fromName(config.sourceKmsRegion())));
        return configuration;
    }

    private static CryptoMode cryptoMode(BatchLambdaConfig.LegacyCryptoMode mode) {
        return switch (mode) {
            case ENCRYPTION_ONLY -> CryptoMode.EncryptionOnly;
            case AUTHENTICATED_ENCRYPTION -> CryptoMode.AuthenticatedEncryption;
            case STRICT_AUTHENTICATED_ENCRYPTION -> CryptoMode.StrictAuthenticatedEncryption;
        };
    }

    private static CryptoStorageMode storageMode(BatchLambdaConfig.LegacyCryptoStorageMode storageMode) {
        return switch (storageMode) {
            case OBJECT_METADATA -> CryptoStorageMode.ObjectMetadata;
            case INSTRUCTION_FILE -> CryptoStorageMode.InstructionFile;
        };
    }

    private static void ensureBouncyCastleProvider() {
        if (Security.getProvider(BC_PROVIDER) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static void createTempDirectory(Path tempDir) {
        try {
            Files.createDirectories(tempDir);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create temp directory " + tempDir, exception);
        }
    }

    private static void cleanup(Path localFile) {
        try {
            Files.deleteIfExists(localFile);
        } catch (IOException ignored) {
            // Lambda /tmp is ephemeral. A later retry should not fail because cleanup of this attempt failed.
        }
    }

    private static boolean isTemporary(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AmazonServiceException serviceException) {
                if (isTemporaryAwsError(serviceException)) {
                    return true;
                }
            } else if (current instanceof SdkClientException || current instanceof IOException) {
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
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                summary.append(": ").append(message);
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
}
