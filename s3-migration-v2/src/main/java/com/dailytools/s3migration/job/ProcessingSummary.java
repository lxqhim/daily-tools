package com.dailytools.s3migration.job;

import com.dailytools.s3migration.state.ProcessingCounters;
import java.time.Instant;

public class ProcessingSummary {

    private long success;
    private long failed;
    private long skipped;
    private long retried;
    private Instant maxLastModified;

    public void add(ObjectProcessResult result) {
        if (result == ObjectProcessResult.SUCCESS) {
            success++;
        } else {
            failed++;
        }
    }

    public void add(ProcessingSummary other) {
        success += other.success;
        failed += other.failed;
        skipped += other.skipped;
        retried += other.retried;
        if (other.maxLastModified != null
                && (maxLastModified == null || other.maxLastModified.isAfter(maxLastModified))) {
            maxLastModified = other.maxLastModified;
        }
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
        return new ProcessingCounters(success, failed, skipped, retried);
    }

    @Override
    public String toString() {
        return counters().toString();
    }
}
