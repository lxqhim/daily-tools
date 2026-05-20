package com.dailytools.s3migration.state;

public enum BaselineStatus {
    NOT_STARTED,
    RUNNING,
    COMPLETED,
    COMPLETED_WITH_FAILURES,
    ABORTED;

    public boolean allowsDelta() {
        return this == COMPLETED || this == COMPLETED_WITH_FAILURES;
    }
}
