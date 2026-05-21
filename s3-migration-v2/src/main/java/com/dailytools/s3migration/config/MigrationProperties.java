package com.dailytools.s3migration.config;

import com.dailytools.s3migration.job.JobMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "migration")
public class MigrationProperties {

    private boolean enabled = true;

    @Valid
    private Job job = new Job();

    @Valid
    private S3 s3 = new S3();

    @Valid
    private Inventory inventory = new Inventory();

    @Valid
    private Shard shard = new Shard();

    @Valid
    private Paths paths = new Paths();

    @Valid
    private Worker worker = new Worker();

    @Valid
    private Upload upload = new Upload();

    @Valid
    private Observability observability = new Observability();

    @Valid
    private Decrypt decrypt = new Decrypt();

    public void validateForRun() {
        if (job.mode == null) {
            throw new IllegalArgumentException("migration.job.mode is required");
        }
        if (isBlank(s3.sourceBucket)) {
            throw new IllegalArgumentException("migration.s3.source-bucket is required");
        }
        if (isBlank(s3.targetBucket)) {
            throw new IllegalArgumentException("migration.s3.target-bucket is required");
        }
        if (shard.index >= shard.total) {
            throw new IllegalArgumentException("migration.shard.index must be less than migration.shard.total");
        }
        if ((job.mode == JobMode.BASELINE || job.mode == JobMode.DELTA) && isBlank(inventory.manifestUri)) {
            throw new IllegalArgumentException("migration.inventory.manifest-uri is required for baseline and delta");
        }
        if (job.mode == JobMode.RETRY && paths.retryInputs.isEmpty()) {
            throw new IllegalArgumentException("migration.paths.retry-inputs is required for retry");
        }
        if (decrypt.legacyKms.enabled && isBlank(decrypt.legacyKms.kmsKeyId)) {
            throw new IllegalArgumentException("migration.decrypt.legacy-kms.kms-key-id is required when legacy KMS decrypt is enabled");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Job getJob() {
        return job;
    }

    public void setJob(Job job) {
        this.job = job;
    }

    public S3 getS3() {
        return s3;
    }

    public void setS3(S3 s3) {
        this.s3 = s3;
    }

    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public Shard getShard() {
        return shard;
    }

    public void setShard(Shard shard) {
        this.shard = shard;
    }

    public Paths getPaths() {
        return paths;
    }

    public void setPaths(Paths paths) {
        this.paths = paths;
    }

    public Worker getWorker() {
        return worker;
    }

    public void setWorker(Worker worker) {
        this.worker = worker;
    }

    public Upload getUpload() {
        return upload;
    }

    public void setUpload(Upload upload) {
        this.upload = upload;
    }

    public Observability getObservability() {
        return observability;
    }

    public void setObservability(Observability observability) {
        this.observability = observability;
    }

    public Decrypt getDecrypt() {
        return decrypt;
    }

    public void setDecrypt(Decrypt decrypt) {
        this.decrypt = decrypt;
    }

    public static class Job {
        @NotNull
        private JobMode mode;
        private String runId;
        private Instant initialWatermark;

        public JobMode getMode() {
            return mode;
        }

        public void setMode(JobMode mode) {
            this.mode = mode;
        }

        public String getRunId() {
            return runId;
        }

        public void setRunId(String runId) {
            this.runId = runId;
        }

        public Instant getInitialWatermark() {
            return initialWatermark;
        }

        public void setInitialWatermark(Instant initialWatermark) {
            this.initialWatermark = initialWatermark;
        }
    }

    public static class S3 {
        @NotBlank
        private String sourceBucket;
        @NotBlank
        private String targetBucket;
        @NotBlank
        private String region = "us-east-1";

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

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }
    }

    public static class Inventory {
        private String manifestUri;

        public String getManifestUri() {
            return manifestUri;
        }

        public void setManifestUri(String manifestUri) {
            this.manifestUri = manifestUri;
        }
    }

    public static class Shard {
        @Min(1)
        private int total = 1;
        @Min(0)
        private int index = 0;

        public int getTotal() {
            return total;
        }

        public void setTotal(int total) {
            this.total = total;
        }

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }
    }

    public static class Paths {
        @NotNull
        private Path state = Path.of("state.json");
        @NotNull
        private Path failedLog = Path.of("failed.log");
        @NotNull
        private Path retryFailedLog = Path.of("failed-retry.log");
        @NotNull
        private Path tempDir = Path.of("tmp");
        private List<Path> retryInputs = new ArrayList<>();

        public Path getState() {
            return state;
        }

        public void setState(Path state) {
            this.state = state;
        }

        public Path getFailedLog() {
            return failedLog;
        }

        public void setFailedLog(Path failedLog) {
            this.failedLog = failedLog;
        }

        public Path getRetryFailedLog() {
            return retryFailedLog;
        }

        public void setRetryFailedLog(Path retryFailedLog) {
            this.retryFailedLog = retryFailedLog;
        }

        public Path getTempDir() {
            return tempDir;
        }

        public void setTempDir(Path tempDir) {
            this.tempDir = tempDir;
        }

        public List<Path> getRetryInputs() {
            return retryInputs;
        }

        public void setRetryInputs(List<Path> retryInputs) {
            this.retryInputs = retryInputs;
        }
    }

    public static class Worker {
        @Min(1)
        private int concurrency = 8;
        @Min(1)
        private int queueSize = 64;

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getQueueSize() {
            return queueSize;
        }

        public void setQueueSize(int queueSize) {
            this.queueSize = queueSize;
        }
    }

    public static class Upload {
        private boolean dryRun;
        @Min(1)
        private int dryRunSampleSize = 100;
        @Min(5_242_880)
        private long multipartThresholdBytes = 134_217_728L;
        @Min(5_242_880)
        private int multipartPartSizeBytes = 67_108_864;

        public boolean isDryRun() {
            return dryRun;
        }

        public void setDryRun(boolean dryRun) {
            this.dryRun = dryRun;
        }

        public int getDryRunSampleSize() {
            return dryRunSampleSize;
        }

        public void setDryRunSampleSize(int dryRunSampleSize) {
            this.dryRunSampleSize = dryRunSampleSize;
        }

        public long getMultipartThresholdBytes() {
            return multipartThresholdBytes;
        }

        public void setMultipartThresholdBytes(long multipartThresholdBytes) {
            this.multipartThresholdBytes = multipartThresholdBytes;
        }

        public int getMultipartPartSizeBytes() {
            return multipartPartSizeBytes;
        }

        public void setMultipartPartSizeBytes(int multipartPartSizeBytes) {
            this.multipartPartSizeBytes = multipartPartSizeBytes;
        }
    }

    public static class Observability {
        @NotNull
        private Duration progressLogInterval = Duration.ofMinutes(5);
        @Min(1)
        private long maxFailedLogBytes = 10_737_418_240L;

        public Duration getProgressLogInterval() {
            return progressLogInterval;
        }

        public void setProgressLogInterval(Duration progressLogInterval) {
            this.progressLogInterval = progressLogInterval;
        }

        public long getMaxFailedLogBytes() {
            return maxFailedLogBytes;
        }

        public void setMaxFailedLogBytes(long maxFailedLogBytes) {
            this.maxFailedLogBytes = maxFailedLogBytes;
        }
    }

    public static class Decrypt {
        @Valid
        private LegacyKms legacyKms = new LegacyKms();

        public LegacyKms getLegacyKms() {
            return legacyKms;
        }

        public void setLegacyKms(LegacyKms legacyKms) {
            this.legacyKms = legacyKms;
        }
    }

    public static class LegacyKms {
        private boolean enabled;
        private String kmsKeyId;
        private String kmsRegion;
        @NotNull
        private LegacyKmsCryptoMode cryptoMode = LegacyKmsCryptoMode.AUTHENTICATED_ENCRYPTION;
        @NotNull
        private LegacyKmsStorageMode storageMode = LegacyKmsStorageMode.OBJECT_METADATA;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getKmsKeyId() {
            return kmsKeyId;
        }

        public void setKmsKeyId(String kmsKeyId) {
            this.kmsKeyId = kmsKeyId;
        }

        public String getKmsRegion() {
            return kmsRegion;
        }

        public void setKmsRegion(String kmsRegion) {
            this.kmsRegion = kmsRegion;
        }

        public LegacyKmsCryptoMode getCryptoMode() {
            return cryptoMode;
        }

        public void setCryptoMode(LegacyKmsCryptoMode cryptoMode) {
            this.cryptoMode = cryptoMode;
        }

        public LegacyKmsStorageMode getStorageMode() {
            return storageMode;
        }

        public void setStorageMode(LegacyKmsStorageMode storageMode) {
            this.storageMode = storageMode;
        }
    }

    public enum LegacyKmsCryptoMode {
        AUTHENTICATED_ENCRYPTION,
        STRICT_AUTHENTICATED_ENCRYPTION
    }

    public enum LegacyKmsStorageMode {
        OBJECT_METADATA,
        INSTRUCTION_FILE
    }
}
