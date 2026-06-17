package com.dailytools.s3migration.batchlambda;

enum BatchResultCode {
    SUCCEEDED("Succeeded"),
    TEMPORARY_FAILURE("TemporaryFailure"),
    PERMANENT_FAILURE("PermanentFailure");

    private final String value;

    BatchResultCode(String value) {
        this.value = value;
    }

    String value() {
        return value;
    }
}
