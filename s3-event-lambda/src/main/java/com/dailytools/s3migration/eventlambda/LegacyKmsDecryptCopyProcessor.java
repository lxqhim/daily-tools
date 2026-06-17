package com.dailytools.s3migration.eventlambda;

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
import com.amazonaws.services.s3.model.PutObjectRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.UUID;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

final class LegacyKmsDecryptCopyProcessor implements S3ObjectProcessor {

    private static final String BC_PROVIDER = "BC";

    private final AmazonS3Encryption sourceClient;
    private final AmazonS3 targetClient;
    private final EventLambdaConfig config;

    LegacyKmsDecryptCopyProcessor(EventLambdaConfig config) {
        this(createSourceClient(config), createTargetClient(config), config);
    }

    LegacyKmsDecryptCopyProcessor(AmazonS3Encryption sourceClient, AmazonS3 targetClient, EventLambdaConfig config) {
        this.sourceClient = sourceClient;
        this.targetClient = targetClient;
        this.config = config;
        createTempDirectory(config.tempDir());
    }

    @Override
    public void process(S3ObjectTask task) throws IOException {
        validateTask(task);
        String targetKey = targetKey(task.sourceKey());
        Path localFile = tempFilePath();
        try {
            GetObjectRequest getObjectRequest = new GetObjectRequest(task.sourceBucket(), task.sourceKey());
            if (hasValue(task.versionId())) {
                getObjectRequest.withVersionId(task.versionId());
            }
            sourceClient.getObject(getObjectRequest, localFile.toFile());
            if (!Files.exists(localFile)) {
                throw new IOException("Decrypt output file was not created: " + localFile);
            }
            targetClient.putObject(new PutObjectRequest(config.targetBucket(), targetKey, localFile.toFile()));
        } finally {
            cleanup(localFile);
        }
    }

    private static void validateTask(S3ObjectTask task) {
        if (task == null) {
            throw new IllegalArgumentException("S3 object task is required");
        }
        if (!hasValue(task.sourceBucket())) {
            throw new IllegalArgumentException("source bucket is required");
        }
        if (!hasValue(task.sourceKey())) {
            throw new IllegalArgumentException("source key is required");
        }
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
        return config.tempDir().resolve("s3-event-decrypt-" + UUID.randomUUID() + ".bin");
    }

    private static AmazonS3Encryption createSourceClient(EventLambdaConfig config) {
        ensureBouncyCastleProvider();
        return AmazonS3EncryptionClientBuilder.standard()
                .withRegion(config.sourceRegion())
                .withCryptoConfiguration(cryptoConfiguration(config))
                .withEncryptionMaterials(new KMSEncryptionMaterialsProvider(config.sourceKmsKeyId()))
                .build();
    }

    private static AmazonS3 createTargetClient(EventLambdaConfig config) {
        return AmazonS3ClientBuilder.standard().withRegion(config.targetRegion()).build();
    }

    private static CryptoConfiguration cryptoConfiguration(EventLambdaConfig config) {
        CryptoConfiguration configuration = new CryptoConfiguration()
                .withCryptoMode(cryptoMode(config.cryptoMode()))
                .withStorageMode(storageMode(config.cryptoStorageMode()));
        configuration.withAwsKmsRegion(Region.getRegion(Regions.fromName(config.sourceKmsRegion())));
        return configuration;
    }

    private static CryptoMode cryptoMode(EventLambdaConfig.LegacyCryptoMode mode) {
        return switch (mode) {
            case ENCRYPTION_ONLY -> CryptoMode.EncryptionOnly;
            case AUTHENTICATED_ENCRYPTION -> CryptoMode.AuthenticatedEncryption;
            case STRICT_AUTHENTICATED_ENCRYPTION -> CryptoMode.StrictAuthenticatedEncryption;
        };
    }

    private static CryptoStorageMode storageMode(EventLambdaConfig.LegacyCryptoStorageMode storageMode) {
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
            // Lambda /tmp is ephemeral. Retry should not fail only because cleanup failed.
        }
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }
}
