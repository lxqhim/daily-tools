package com.dailytools.s3migration.eventlambda;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

final class EventLambdaConfig {

    static final String DEFAULT_TEMP_DIR = "/tmp/s3-event-decrypt";

    private final String sourceRegion;
    private final String targetRegion;
    private final String sourceKmsRegion;
    private final String sourceKmsKeyId;
    private final String targetBucket;
    private final String targetKeyPrefix;
    private final Path tempDir;
    private final LegacyCryptoMode cryptoMode;
    private final LegacyCryptoStorageMode cryptoStorageMode;

    private EventLambdaConfig(
            String sourceRegion,
            String targetRegion,
            String sourceKmsRegion,
            String sourceKmsKeyId,
            String targetBucket,
            String targetKeyPrefix,
            Path tempDir,
            LegacyCryptoMode cryptoMode,
            LegacyCryptoStorageMode cryptoStorageMode) {
        this.sourceRegion = sourceRegion;
        this.targetRegion = targetRegion;
        this.sourceKmsRegion = sourceKmsRegion;
        this.sourceKmsKeyId = sourceKmsKeyId;
        this.targetBucket = targetBucket;
        this.targetKeyPrefix = targetKeyPrefix;
        this.tempDir = tempDir;
        this.cryptoMode = cryptoMode;
        this.cryptoStorageMode = cryptoStorageMode;
    }

    static EventLambdaConfig fromEnvironment() {
        return from(System.getenv());
    }

    static EventLambdaConfig from(Map<String, String> env) {
        String lambdaRegion = value(env, "AWS_REGION", "us-east-1");
        String sourceRegion = value(env, "SOURCE_REGION", lambdaRegion);
        String targetRegion = value(env, "TARGET_REGION", sourceRegion);
        String sourceKmsRegion = value(env, "SOURCE_KMS_REGION", sourceRegion);
        String sourceKmsKeyId = required(env, "SOURCE_KMS_KEY_ID");
        String targetBucket = required(env, "TARGET_BUCKET");
        String targetKeyPrefix = trimToEmpty(env.get("TARGET_KEY_PREFIX"));
        Path tempDir = Path.of(value(env, "TEMP_DIR", DEFAULT_TEMP_DIR));
        LegacyCryptoMode cryptoMode =
                LegacyCryptoMode.from(value(env, "CRYPTO_MODE", LegacyCryptoMode.ENCRYPTION_ONLY.name()));
        LegacyCryptoStorageMode cryptoStorageMode = LegacyCryptoStorageMode.from(
                value(env, "CRYPTO_STORAGE_MODE", LegacyCryptoStorageMode.OBJECT_METADATA.name()));
        return new EventLambdaConfig(
                sourceRegion,
                targetRegion,
                sourceKmsRegion,
                sourceKmsKeyId,
                targetBucket,
                targetKeyPrefix,
                tempDir,
                cryptoMode,
                cryptoStorageMode);
    }

    String sourceRegion() {
        return sourceRegion;
    }

    String targetRegion() {
        return targetRegion;
    }

    String sourceKmsRegion() {
        return sourceKmsRegion;
    }

    String sourceKmsKeyId() {
        return sourceKmsKeyId;
    }

    String targetBucket() {
        return targetBucket;
    }

    String targetKeyPrefix() {
        return targetKeyPrefix;
    }

    Path tempDir() {
        return tempDir;
    }

    LegacyCryptoMode cryptoMode() {
        return cryptoMode;
    }

    LegacyCryptoStorageMode cryptoStorageMode() {
        return cryptoStorageMode;
    }

    private static String required(Map<String, String> env, String name) {
        String value = trimToEmpty(env.get(name));
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing required environment variable " + name);
        }
        return value;
    }

    private static String value(Map<String, String> env, String name, String defaultValue) {
        String value = trimToEmpty(env.get(name));
        return value.isBlank() ? defaultValue : value;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    enum LegacyCryptoMode {
        ENCRYPTION_ONLY,
        AUTHENTICATED_ENCRYPTION,
        STRICT_AUTHENTICATED_ENCRYPTION;

        static LegacyCryptoMode from(String value) {
            return LegacyCryptoMode.valueOf(normalize(value));
        }
    }

    enum LegacyCryptoStorageMode {
        OBJECT_METADATA,
        INSTRUCTION_FILE;

        static LegacyCryptoStorageMode from(String value) {
            return LegacyCryptoStorageMode.valueOf(normalize(value));
        }
    }

    private static String normalize(String value) {
        return value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }
}
