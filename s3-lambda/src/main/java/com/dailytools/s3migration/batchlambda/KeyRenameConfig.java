package com.dailytools.s3migration.batchlambda;

import java.util.Map;

/** Configuration for copying objects to a key with one path segment renamed. */
final class KeyRenameConfig {

    private static final int DEFAULT_RESULT_STRING_MAX_LENGTH = 1024;
    private static final int DEFAULT_SOURCE_SEGMENT_INDEX = 3;

    private final String region;
    private final String sourceSegment;
    private final String targetSegment;
    private final int sourceSegmentIndex;
    private final int resultStringMaxLength;

    private KeyRenameConfig(
            String region, String sourceSegment, String targetSegment, int sourceSegmentIndex, int resultStringMaxLength) {
        this.region = region;
        this.sourceSegment = sourceSegment;
        this.targetSegment = targetSegment;
        this.sourceSegmentIndex = sourceSegmentIndex;
        this.resultStringMaxLength = resultStringMaxLength;
    }

    static KeyRenameConfig fromEnvironment() {
        return from(System.getenv());
    }

    static KeyRenameConfig from(Map<String, String> environment) {
        String region = value(environment, "AWS_REGION", "us-east-1");
        String sourceSegment = segment(required(environment, "RENAME_SOURCE_SEGMENT"), "RENAME_SOURCE_SEGMENT");
        String targetSegment = segment(required(environment, "RENAME_TARGET_SEGMENT"), "RENAME_TARGET_SEGMENT");
        if (sourceSegment.equals(targetSegment)) {
            throw new IllegalArgumentException("RENAME_SOURCE_SEGMENT and RENAME_TARGET_SEGMENT must differ");
        }
        int sourceSegmentIndex = parsePositiveInt(
                value(environment, "RENAME_SOURCE_SEGMENT_INDEX", String.valueOf(DEFAULT_SOURCE_SEGMENT_INDEX)),
                "RENAME_SOURCE_SEGMENT_INDEX");
        int resultStringMaxLength = parsePositiveInt(
                value(environment, "RESULT_STRING_MAX_LENGTH", String.valueOf(DEFAULT_RESULT_STRING_MAX_LENGTH)),
                "RESULT_STRING_MAX_LENGTH");
        return new KeyRenameConfig(region, sourceSegment, targetSegment, sourceSegmentIndex, resultStringMaxLength);
    }

    String region() {
        return region;
    }

    String sourceSegment() {
        return sourceSegment;
    }

    String targetSegment() {
        return targetSegment;
    }

    /** One-based slash-delimited key segment index. */
    int sourceSegmentIndex() {
        return sourceSegmentIndex;
    }

    int resultStringMaxLength() {
        return resultStringMaxLength;
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required environment variable: " + name);
        }
        return value.trim();
    }

    private static String value(Map<String, String> environment, String name, String defaultValue) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String segment(String value, String environmentVariable) {
        if (value.indexOf('/') >= 0) {
            throw new IllegalArgumentException(environmentVariable + " must be one S3 key path segment");
        }
        return value;
    }

    private static int parsePositiveInt(String value, String environmentVariable) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(environmentVariable + " must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(environmentVariable + " must be a positive integer", exception);
        }
    }
}
