package com.dailytools.s3migration.batchlambda;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class BatchLambdaConfig {

    static final String DEFAULT_TEMP_DIR = "/tmp/s3-batch-decrypt";

    private final String sourceRegion;
    private final String targetRegion;
    private final String sourceKmsRegion;
    private final String sourceKmsKeyId;
    private final String targetBucket;
    private final String targetKeyPrefix;
    private final Path tempDir;
    private final LegacyCryptoMode cryptoMode;
    private final LegacyCryptoStorageMode cryptoStorageMode;
    private final int resultStringMaxLength;
    private final Set<String> copyMetadataKeys;

    private BatchLambdaConfig(
            String sourceRegion,
            String targetRegion,
            String sourceKmsRegion,
            String sourceKmsKeyId,
            String targetBucket,
            String targetKeyPrefix,
            Path tempDir,
            LegacyCryptoMode cryptoMode,
            LegacyCryptoStorageMode cryptoStorageMode,
            int resultStringMaxLength,
            Set<String> copyMetadataKeys) {
        this.sourceRegion = sourceRegion;
        this.targetRegion = targetRegion;
        this.sourceKmsRegion = sourceKmsRegion;
        this.sourceKmsKeyId = sourceKmsKeyId;
        this.targetBucket = targetBucket;
        this.targetKeyPrefix = targetKeyPrefix;
        this.tempDir = tempDir;
        this.cryptoMode = cryptoMode;
        this.cryptoStorageMode = cryptoStorageMode;
        this.resultStringMaxLength = resultStringMaxLength;
        this.copyMetadataKeys = copyMetadataKeys;
    }

    static BatchLambdaConfig fromEnvironment() {
        return from(System.getenv());
    }

    static BatchLambdaConfig from(Map<String, String> env) {
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
        int resultStringMaxLength = parsePositiveInt(value(env, "RESULT_STRING_MAX_LENGTH", "1024"));
        Set<String> copyMetadataKeys = parseMetadataKeys(env.get("COPY_METADATA_KEYS"));
        return new BatchLambdaConfig(
                sourceRegion,
                targetRegion,
                sourceKmsRegion,
                sourceKmsKeyId,
                targetBucket,
                targetKeyPrefix,
                tempDir,
                cryptoMode,
                cryptoStorageMode,
                resultStringMaxLength,
                copyMetadataKeys);
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

    int resultStringMaxLength() {
        return resultStringMaxLength;
    }

    Set<String> copyMetadataKeys() {
        return copyMetadataKeys;
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

    private static int parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new IllegalArgumentException("RESULT_STRING_MAX_LENGTH must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("RESULT_STRING_MAX_LENGTH must be an integer", exception);
        }
    }

    private static Set<String> parseMetadataKeys(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String item : value.split(",")) {
            String key = normalizeMetadataKey(item);
            if (!key.isBlank()) {
                keys.add(key);
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    static String normalizeMetadataKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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
