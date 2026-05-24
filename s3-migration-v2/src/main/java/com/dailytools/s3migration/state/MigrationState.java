package com.dailytools.s3migration.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.dailytools.s3migration.job.JobMode;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

public class MigrationState {

    private int shardTotal;
    private int shardIndex;
    private String sourceBucket;
    private String targetBucket;
    private JobMode currentMode;
    private String baselineManifestUri;
    private Instant baselineInventoryTimestamp;
    private Instant baselineObservedMaxLastModified;
    private BaselineStatus baselineStatus = BaselineStatus.NOT_STARTED;
    private long baselineCompletedFileCount;
    private String baselineLastCompletedFile;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Set<String> baselineCompletedFiles = new LinkedHashSet<>();
    private String deltaManifestUri;
    private long deltaCompletedFileCount;
    private String deltaLastCompletedFile;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Set<String> deltaCompletedFiles = new LinkedHashSet<>();
    private Instant deltaWatermark;
    private Instant deltaObservedMaxLastModified;
    private Instant deltaCandidateWatermark;
    private Counters counters = new Counters();
    private Instant jobStartedAt;
    private Instant lastCheckpointAt;
    private Instant jobCompletedAt;
    private String lastErrorCode;
    private String lastErrorMessage;
    private Instant lastErrorAt;

    public int getShardTotal() {
        return shardTotal;
    }

    public void setShardTotal(int shardTotal) {
        this.shardTotal = shardTotal;
    }

    public int getShardIndex() {
        return shardIndex;
    }

    public void setShardIndex(int shardIndex) {
        this.shardIndex = shardIndex;
    }

    public String getSourceBucket() {
        return sourceBucket;
    }

    public void setSourceBucket(String sourceBucket) {
        this.sourceBucket = sourceBucket;
    }

    public String getTargetBucket() {
        return targetBucket;
    }

    public void setTargetBucket(String targetBucket) {
        this.targetBucket = targetBucket;
    }

    public JobMode getCurrentMode() {
        return currentMode;
    }

    public void setCurrentMode(JobMode currentMode) {
        this.currentMode = currentMode;
    }

    public String getBaselineManifestUri() {
        return baselineManifestUri;
    }

    public void setBaselineManifestUri(String baselineManifestUri) {
        this.baselineManifestUri = baselineManifestUri;
    }

    public Instant getBaselineInventoryTimestamp() {
        return baselineInventoryTimestamp;
    }

    public void setBaselineInventoryTimestamp(Instant baselineInventoryTimestamp) {
        this.baselineInventoryTimestamp = baselineInventoryTimestamp;
    }

    public Instant getBaselineObservedMaxLastModified() {
        return baselineObservedMaxLastModified;
    }

    public void setBaselineObservedMaxLastModified(Instant baselineObservedMaxLastModified) {
        this.baselineObservedMaxLastModified = baselineObservedMaxLastModified;
    }

    public BaselineStatus getBaselineStatus() {
        return baselineStatus;
    }

    public void setBaselineStatus(BaselineStatus baselineStatus) {
        this.baselineStatus = baselineStatus;
    }

    public long getBaselineCompletedFileCount() {
        return baselineCompletedFileCount;
    }

    public void setBaselineCompletedFileCount(long baselineCompletedFileCount) {
        this.baselineCompletedFileCount = baselineCompletedFileCount;
    }

    public String getBaselineLastCompletedFile() {
        return baselineLastCompletedFile;
    }

    public void setBaselineLastCompletedFile(String baselineLastCompletedFile) {
        this.baselineLastCompletedFile = baselineLastCompletedFile;
    }

    public Set<String> getBaselineCompletedFiles() {
        return baselineCompletedFiles;
    }

    public void setBaselineCompletedFiles(Set<String> baselineCompletedFiles) {
        this.baselineCompletedFiles = baselineCompletedFiles;
    }

    public String getDeltaManifestUri() {
        return deltaManifestUri;
    }

    public void setDeltaManifestUri(String deltaManifestUri) {
        this.deltaManifestUri = deltaManifestUri;
    }

    public long getDeltaCompletedFileCount() {
        return deltaCompletedFileCount;
    }

    public void setDeltaCompletedFileCount(long deltaCompletedFileCount) {
        this.deltaCompletedFileCount = deltaCompletedFileCount;
    }

    public String getDeltaLastCompletedFile() {
        return deltaLastCompletedFile;
    }

    public void setDeltaLastCompletedFile(String deltaLastCompletedFile) {
        this.deltaLastCompletedFile = deltaLastCompletedFile;
    }

    public Set<String> getDeltaCompletedFiles() {
        return deltaCompletedFiles;
    }

    public void setDeltaCompletedFiles(Set<String> deltaCompletedFiles) {
        this.deltaCompletedFiles = deltaCompletedFiles;
    }

    public Instant getDeltaWatermark() {
        return deltaWatermark;
    }

    public void setDeltaWatermark(Instant deltaWatermark) {
        this.deltaWatermark = deltaWatermark;
    }

    public Instant getDeltaObservedMaxLastModified() {
        return deltaObservedMaxLastModified;
    }

    public void setDeltaObservedMaxLastModified(Instant deltaObservedMaxLastModified) {
        this.deltaObservedMaxLastModified = deltaObservedMaxLastModified;
    }

    public Instant getDeltaCandidateWatermark() {
        return deltaCandidateWatermark;
    }

    public void setDeltaCandidateWatermark(Instant deltaCandidateWatermark) {
        this.deltaCandidateWatermark = deltaCandidateWatermark;
    }

    public Counters getCounters() {
        return counters;
    }

    public void setCounters(Counters counters) {
        this.counters = counters;
    }

    public Instant getJobStartedAt() {
        return jobStartedAt;
    }

    public void setJobStartedAt(Instant jobStartedAt) {
        this.jobStartedAt = jobStartedAt;
    }

    public Instant getLastCheckpointAt() {
        return lastCheckpointAt;
    }

    public void setLastCheckpointAt(Instant lastCheckpointAt) {
        this.lastCheckpointAt = lastCheckpointAt;
    }

    public Instant getJobCompletedAt() {
        return jobCompletedAt;
    }

    public void setJobCompletedAt(Instant jobCompletedAt) {
        this.jobCompletedAt = jobCompletedAt;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public void setLastErrorCode(String lastErrorCode) {
        this.lastErrorCode = lastErrorCode;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public void setLastErrorMessage(String lastErrorMessage) {
        this.lastErrorMessage = lastErrorMessage;
    }

    public Instant getLastErrorAt() {
        return lastErrorAt;
    }

    public void setLastErrorAt(Instant lastErrorAt) {
        this.lastErrorAt = lastErrorAt;
    }

    @JsonIgnore
    public long completedFileCount(JobMode mode) {
        return mode == JobMode.BASELINE ? baselineCompletedFileCount : deltaCompletedFileCount;
    }

    @JsonIgnore
    public String lastCompletedFile(JobMode mode) {
        return mode == JobMode.BASELINE ? baselineLastCompletedFile : deltaLastCompletedFile;
    }

    public void markFileCompleted(JobMode mode, String key) {
        if (mode == JobMode.BASELINE) {
            baselineCompletedFileCount++;
            baselineLastCompletedFile = key;
        } else if (mode == JobMode.DELTA) {
            deltaCompletedFileCount++;
            deltaLastCompletedFile = key;
        }
    }

    public void resetDeltaProgress() {
        deltaCompletedFileCount = 0;
        deltaLastCompletedFile = null;
        deltaCompletedFiles.clear();
    }

    public void migrateCompletedFileSetsToCursors() {
        if (baselineCompletedFileCount == 0 && !baselineCompletedFiles.isEmpty()) {
            baselineCompletedFileCount = baselineCompletedFiles.size();
            baselineLastCompletedFile = lastOf(baselineCompletedFiles);
        }
        if (deltaCompletedFileCount == 0 && !deltaCompletedFiles.isEmpty()) {
            deltaCompletedFileCount = deltaCompletedFiles.size();
            deltaLastCompletedFile = lastOf(deltaCompletedFiles);
        }
        baselineCompletedFiles.clear();
        deltaCompletedFiles.clear();
    }

    private static String lastOf(Set<String> values) {
        String last = null;
        for (String value : values) {
            last = value;
        }
        return last;
    }

    public static class Counters {
        private long success;
        private long dryRunSuccess;
        private long failed;
        private long skipped;
        private long retried;

        public long getSuccess() {
            return success;
        }

        public void setSuccess(long success) {
            this.success = success;
        }

        public long getDryRunSuccess() {
            return dryRunSuccess;
        }

        public void setDryRunSuccess(long dryRunSuccess) {
            this.dryRunSuccess = dryRunSuccess;
        }

        public long getFailed() {
            return failed;
        }

        public void setFailed(long failed) {
            this.failed = failed;
        }

        public long getSkipped() {
            return skipped;
        }

        public void setSkipped(long skipped) {
            this.skipped = skipped;
        }

        public long getRetried() {
            return retried;
        }

        public void setRetried(long retried) {
            this.retried = retried;
        }

        public void add(ProcessingCounters counters) {
            success += counters.success();
            dryRunSuccess += counters.dryRunSuccess();
            failed += counters.failed();
            skipped += counters.skipped();
            retried += counters.retried();
        }

        @Override
        public String toString() {
            return "Counters[success=" + success + ", dryRunSuccess=" + dryRunSuccess + ", failed=" + failed
                    + ", skipped=" + skipped + ", retried=" + retried + "]";
        }
    }
}
