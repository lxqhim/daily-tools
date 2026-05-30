package com.dailytools.s3migration.job;

import java.time.Duration;
import java.time.Instant;

class DataFileProgress {

    private final Instant startedAt;
    private final Duration logInterval;
    private Instant nextLogAt;
    private long scannedRows;
    private long submittedRows;

    DataFileProgress(Instant startedAt, Duration logInterval) {
        this.startedAt = startedAt;
        this.logInterval = logInterval;
        this.nextLogAt = startedAt.plus(logInterval);
    }

    void scanned() {
        scannedRows++;
    }

    void submitted() {
        submittedRows++;
    }

    boolean shouldLog(Instant now) {
        if (!isLoggingEnabled()) {
            return false;
        }
        if (now.isBefore(nextLogAt)) {
            return false;
        }
        do {
            nextLogAt = nextLogAt.plus(logInterval);
        } while (!now.isBefore(nextLogAt));
        return true;
    }

    boolean isLoggingEnabled() {
        return !logInterval.isZero() && !logInterval.isNegative();
    }

    long pollMillisUntilNextLog(Instant now) {
        long millis = Duration.between(now, nextLogAt).toMillis();
        return millis < 1L ? 1L : millis;
    }

    long scannedRows() {
        return scannedRows;
    }

    long submittedRows() {
        return submittedRows;
    }

    Duration elapsed(Instant now) {
        return Duration.between(startedAt, now);
    }
}
