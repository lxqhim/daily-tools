package com.dailytools.s3migration.job;

import com.dailytools.s3migration.state.ProcessingCounters;
import java.time.Instant;

public class ProcessingSummary {

    private long success;
    private long dryRunSuccess;
    private long failed;
    private long skipped;
    private long retried;
    private long scannedRows;
    private long submittedRows;
    private Instant maxLastModified;

    public void add(ObjectProcessResult result) {
        if (result == ObjectProcessResult.SUCCESS) {
            success++;
        } else if (result == ObjectProcessResult.DRY_RUN_SUCCESS) {
            dryRunSuccess++;
        } else if (result == ObjectProcessResult.FAILED) {
            failed++;
        }
    }

    public void add(ProcessingSummary other) {
        success += other.success;
        dryRunSuccess += other.dryRunSuccess;
        failed += other.failed;
        skipped += other.skipped;
        retried += other.retried;
        scannedRows += other.scannedRows;
        submittedRows += other.submittedRows;
        if (other.maxLastModified != null
                && (maxLastModified == null || other.maxLastModified.isAfter(maxLastModified))) {
            maxLastModified = other.maxLastModified;
        }
    }

    public void scanned() {
        scannedRows++;
    }

    public void submitted() {
        submittedRows++;
    }

    public void skipped() {
        skipped++;
    }

    public void retried() {
        retried++;
    }

    public void observeLastModified(Instant instant) {
        if (instant != null && (maxLastModified == null || instant.isAfter(maxLastModified))) {
            maxLastModified = instant;
        }
    }

    public boolean hasFailures() {
        return failed > 0;
    }

    public Instant maxLastModified() {
        return maxLastModified;
    }

    public ProcessingCounters counters() {
        return new ProcessingCounters(success, dryRunSuccess, failed, skipped, retried);
    }

    public long scannedRows() {
        return scannedRows;
    }

    public long submittedRows() {
        return submittedRows;
    }

    @Override
    public String toString() {
        return "ProcessingSummary[counters="
                + counters()
                + ", scannedRows="
                + scannedRows
                + ", submittedRows="
                + submittedRows
                + "]";
    }
}
