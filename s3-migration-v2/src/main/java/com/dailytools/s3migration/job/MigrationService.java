package com.dailytools.s3migration.job;

import com.dailytools.s3migration.config.MigrationProperties;
import com.dailytools.s3migration.failed.FailedLog;
import com.dailytools.s3migration.failed.FailedRecord;
import com.dailytools.s3migration.inventory.InventoryCsvParser;
import com.dailytools.s3migration.inventory.InventoryDataFile;
import com.dailytools.s3migration.inventory.InventoryManifest;
import com.dailytools.s3migration.inventory.InventoryObject;
import com.dailytools.s3migration.inventory.InventoryRowParseFailure;
import com.dailytools.s3migration.inventory.S3InventoryManifestReader;
import com.dailytools.s3migration.s3.S3ObjectReader;
import com.dailytools.s3migration.s3.S3Uri;
import com.dailytools.s3migration.shard.HashShardAssigner;
import com.dailytools.s3migration.state.BaselineStatus;
import com.dailytools.s3migration.state.MigrationState;
import com.dailytools.s3migration.state.StateStore;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    private final MigrationProperties properties;
    private final StateStore stateStore;
    private final S3InventoryManifestReader manifestReader;
    private final S3ObjectReader objectReader;
    private final InventoryCsvParser csvParser;
    private final HashShardAssigner shardAssigner;
    private final ObjectProcessor objectProcessor;
    private final FailedLog failedLog;
    private final WatermarkPolicy watermarkPolicy;

    public MigrationService(
            MigrationProperties properties,
            StateStore stateStore,
            S3InventoryManifestReader manifestReader,
            S3ObjectReader objectReader,
            InventoryCsvParser csvParser,
            HashShardAssigner shardAssigner,
            ObjectProcessor objectProcessor,
            FailedLog failedLog,
            WatermarkPolicy watermarkPolicy) {
        this.properties = properties;
        this.stateStore = stateStore;
        this.manifestReader = manifestReader;
        this.objectReader = objectReader;
        this.csvParser = csvParser;
        this.shardAssigner = shardAssigner;
        this.objectProcessor = objectProcessor;
        this.failedLog = failedLog;
        this.watermarkPolicy = watermarkPolicy;
    }

    public void run() throws Exception {
        if (properties.getUpload().isDryRun()) {
            runUploadDryRun();
            return;
        }
        switch (properties.getJob().getMode()) {
            case BASELINE -> runBaseline();
            case DELTA -> runDelta();
            case RETRY -> runRetry();
        }
    }

    private void runUploadDryRun() throws Exception {
        switch (properties.getJob().getMode()) {
            case BASELINE -> runBaselineUploadDryRun();
            case DELTA -> runDeltaUploadDryRun();
            case RETRY -> runRetryUploadDryRun();
        }
    }

    private void runBaselineUploadDryRun() throws Exception {
        String runId = runId();
        S3Uri manifestUri = S3Uri.parse(properties.getInventory().getManifestUri());
        DryRunSampleLimiter sampleLimiter = dryRunSampleLimiter();
        log.info(
                "Starting baseline upload dry-run runId={} dryRun=true shard={}/{} manifest={} dryRunSampleSize={} stateCheckpointing=false",
                runId,
                properties.getShard().getIndex(),
                properties.getShard().getTotal(),
                manifestUri.toUriString(),
                sampleLimiter.limit());

        ProcessingSummary total = new ProcessingSummary();
        InventoryManifest manifest = manifestReader.read(manifestUri);
        processManifestFiles(
                dryRunState(),
                manifestUri.bucket(),
                manifest,
                JobMode.BASELINE,
                runId,
                null,
                total,
                false,
                sampleLimiter);
        log.info(
                "Completed baseline upload dry-run runId={} dryRun=true shard={}/{} summary={} scannedRows={} submittedRows={} sampledObjects={} dryRunSampleSize={} sampleLimitReached={} stateWritten=false",
                runId,
                properties.getShard().getIndex(),
                properties.getShard().getTotal(),
                total.counters(),
                total.scannedRows(),
                total.submittedRows(),
                sampleLimiter.submitted(),
                sampleLimiter.limit(),
                sampleLimiter.reached());
    }

    private void runDeltaUploadDryRun() throws Exception {
        MigrationState existingState = stateStore.load(properties.getPaths().getState(), properties);
        if (!existingState.getBaselineStatus().allowsDelta()) {
            throw new IllegalStateException("Delta requires baseline status COMPLETED or COMPLETED_WITH_FAILURES");
        }

        String runId = runId();
        S3Uri manifestUri = S3Uri.parse(properties.getInventory().getManifestUri());
        DryRunSampleLimiter sampleLimiter = dryRunSampleLimiter();
        Instant previousWatermark = existingState.getDeltaWatermark();
        if (previousWatermark == null) {
            previousWatermark = watermarkPolicy.initialForDeltaWhenStateMissing();
        }
        log.info(
                "Starting delta upload dry-run runId={} dryRun=true shard={}/{} manifest={} previousWatermark={} dryRunSampleSize={} stateCheckpointing=false",
                runId,
                properties.getShard().getIndex(),
                properties.getShard().getTotal(),
                manifestUri.toUriString(),
                previousWatermark,
                sampleLimiter.limit());

        ProcessingSummary total = new ProcessingSummary();
        InventoryManifest manifest = manifestReader.read(manifestUri);
        processManifestFiles(
                dryRunState(),
                manifestUri.bucket(),
                manifest,
                JobMode.DELTA,
                runId,
                previousWatermark,
                total,
                false,
                sampleLimiter);
        log.info(
                "Completed delta upload dry-run runId={} dryRun=true shard={}/{} summary={} scannedRows={} submittedRows={} candidateWatermark={} sampledObjects={} dryRunSampleSize={} sampleLimitReached={} stateWritten=false",
                runId,
                properties.getShard().getIndex(),
                properties.getShard().getTotal(),
                total.counters(),
                total.scannedRows(),
                total.submittedRows(),
                watermarkPolicy.advanceAfterDelta(previousWatermark, total),
                sampleLimiter.submitted(),
                sampleLimiter.limit(),
                sampleLimiter.reached());
    }

    private void runRetryUploadDryRun() throws Exception {
        String runId = runId();
        DryRunSampleLimiter sampleLimiter = dryRunSampleLimiter();
        log.info(
                "Starting retry upload dry-run runId={} dryRun=true shard={}/{} inputs={} dryRunSampleSize={} stateCheckpointing=false",
                runId,
                properties.getShard().getIndex(),
                properties.getShard().getTotal(),
                properties.getPaths().getRetryInputs(),
                sampleLimiter.limit());
        ProcessingSummary summary = new ProcessingSummary();
        try {
            failedLog.readEach(properties.getPaths().getRetryInputs(), record -> {
                if (!sampleLimiter.tryReserve()) {
                    summary.skipped();
                    throw DryRunSampleLimitReached.INSTANCE;
                }
                InventoryObject object = new InventoryObject(
                        properties.getS3().getSourceBucket(),
                        record.key(),
                        record.lastModified(),
                        record.size(),
                        record.eTag());
                summary.retried();
                summary.add(
                        objectProcessor.process(object, JobMode.RETRY, runId, properties.getPaths().getRetryFailedLog()));
            });
        } catch (DryRunSampleLimitReached exception) {
            log.info(
                    "Upload dry-run sample size reached mode={} runId={} sampledObjects={} dryRunSampleSize={} uploadDryRun=true",
                    JobMode.RETRY,
                    runId,
                    sampleLimiter.submitted(),
                    sampleLimiter.limit());
        }
        log.info(
                "Completed retry upload dry-run runId={} dryRun=true shard={}/{} summary={} sampledObjects={} dryRunSampleSize={} sampleLimitReached={} stateWritten=false",
                runId,
                properties.getShard().getIndex(),
                properties.getShard().getTotal(),
                summary.counters(),
                sampleLimiter.submitted(),
                sampleLimiter.limit(),
                sampleLimiter.reached());
    }

    private void runBaseline() throws Exception {
        MigrationState state = stateStore.loadOrCreate(properties.getPaths().getState(), properties);
        if (state.getBaselineStatus() == BaselineStatus.COMPLETED
                || state.getBaselineStatus() == BaselineStatus.COMPLETED_WITH_FAILURES) {
            return;
        }
        String runId = runId();
        S3Uri manifestUri = S3Uri.parse(properties.getInventory().getManifestUri());
        log.info(
                "Starting baseline runId={} dryRun=false shard={}/{} manifest={}",
                runId,
                properties.getShard().getIndex(),
                properties.getShard().getTotal(),
                manifestUri.toUriString());
        state.setCurrentMode(JobMode.BASELINE);
        state.setBaselineStatus(BaselineStatus.RUNNING);
        state.setBaselineManifestUri(manifestUri.toUriString());
        clearLastError(state);
        state.setLastCheckpointAt(Instant.now());
        stateStore.write(properties.getPaths().getState(), state);

        ProcessingSummary total = new ProcessingSummary();
        total.observeLastModified(state.getBaselineObservedMaxLastModified());
        try {
            InventoryManifest manifest = manifestReader.read(manifestUri);
            state.setBaselineInventoryTimestamp(manifest.creationTimestamp());
            processManifestFiles(
                    state,
                    manifestUri.bucket(),
                    manifest,
                    JobMode.BASELINE,
                    runId,
                    null,
                    total);
            state.setDeltaWatermark(watermarkPolicy.initialAfterBaseline(total));
            state.setDeltaCandidateWatermark(state.getDeltaWatermark());
            log.info(
                    "Initialized delta watermark from baseline runId={} deltaWatermark={} observedMaxLastModified={}",
                    runId,
                    state.getDeltaWatermark(),
                    state.getBaselineObservedMaxLastModified());
            state.setBaselineStatus(
                    state.getCounters().getFailed() > 0 ? BaselineStatus.COMPLETED_WITH_FAILURES : BaselineStatus.COMPLETED);
            state.setJobCompletedAt(Instant.now());
            state.setLastCheckpointAt(Instant.now());
            stateStore.write(properties.getPaths().getState(), state);
            log.info(
                    "Completed baseline runId={} dryRun=false status={} shard={}/{} counters={} scannedRows={} submittedRows={}",
                    runId,
                    state.getBaselineStatus(),
                    properties.getShard().getIndex(),
                    properties.getShard().getTotal(),
                    state.getCounters(),
                    total.scannedRows(),
                    total.submittedRows());
        } catch (Exception exception) {
            state.setBaselineStatus(BaselineStatus.ABORTED);
            recordRunFailure(state, "BASELINE_ABORTED", exception);
            state.setLastCheckpointAt(Instant.now());
            stateStore.write(properties.getPaths().getState(), state);
            log.error("Baseline aborted runId={} manifest={}", runId, manifestUri.toUriString(), exception);
            throw exception;
        }
    }

    private void runDelta() throws Exception {
        MigrationState state = stateStore.loadOrCreate(properties.getPaths().getState(), properties);
        if (!state.getBaselineStatus().allowsDelta()) {
            throw new IllegalStateException("Delta requires baseline status COMPLETED or COMPLETED_WITH_FAILURES");
        }

        String runId = runId();
        S3Uri manifestUri = S3Uri.parse(properties.getInventory().getManifestUri());
        log.info(
                "Starting delta runId={} dryRun=false shard={}/{} manifest={}",
                runId,
                properties.getShard().getIndex(),
                properties.getShard().getTotal(),
                manifestUri.toUriString());
        if (!manifestUri.toUriString().equals(state.getDeltaManifestUri())) {
            state.setDeltaManifestUri(manifestUri.toUriString());
            state.resetDeltaProgress();
            state.setDeltaObservedMaxLastModified(null);
            state.setDeltaCandidateWatermark(null);
        }
        state.setCurrentMode(JobMode.DELTA);
        clearLastError(state);
        state.setLastCheckpointAt(Instant.now());
        stateStore.write(properties.getPaths().getState(), state);

        try {
            InventoryManifest manifest = manifestReader.read(manifestUri);
            Instant previousWatermark = state.getDeltaWatermark();
            if (previousWatermark == null) {
                previousWatermark = watermarkPolicy.initialForDeltaWhenStateMissing();
            }
            log.info(
                    "Loaded delta watermark runId={} previousWatermark={} observedMaxLastModified={} candidateWatermark={}",
                    runId,
                    previousWatermark,
                    state.getDeltaObservedMaxLastModified(),
                    state.getDeltaCandidateWatermark());
            ProcessingSummary total = new ProcessingSummary();
            total.observeLastModified(state.getDeltaObservedMaxLastModified());
            processManifestFiles(
                    state,
                    manifestUri.bucket(),
                    manifest,
                    JobMode.DELTA,
                    runId,
                    previousWatermark,
                    total);
            state.setDeltaWatermark(watermarkPolicy.advanceAfterDelta(previousWatermark, total));
            state.setDeltaCandidateWatermark(state.getDeltaWatermark());
            state.setJobCompletedAt(Instant.now());
            state.setLastCheckpointAt(Instant.now());
            stateStore.write(properties.getPaths().getState(), state);
            log.info(
                    "Completed delta runId={} dryRun=false shard={}/{} previousWatermark={} newWatermark={} counters={} scannedRows={} submittedRows={}",
                    runId,
                    properties.getShard().getIndex(),
                    properties.getShard().getTotal(),
                    previousWatermark,
                    state.getDeltaWatermark(),
                    state.getCounters(),
                    total.scannedRows(),
                    total.submittedRows());
        } catch (Exception exception) {
            recordRunFailure(state, "DELTA_ABORTED", exception);
            state.setLastCheckpointAt(Instant.now());
            stateStore.write(properties.getPaths().getState(), state);
            log.error("Delta aborted runId={} manifest={}", runId, manifestUri.toUriString(), exception);
            throw exception;
        }
    }

    private void runRetry() throws Exception {
        MigrationState state = stateStore.loadOrCreate(properties.getPaths().getState(), properties);
        String runId = runId();
        log.info(
                "Starting retry runId={} dryRun=false shard={}/{} inputs={}",
                runId,
                properties.getShard().getIndex(),
                properties.getShard().getTotal(),
                properties.getPaths().getRetryInputs());
        state.setCurrentMode(JobMode.RETRY);
        clearLastError(state);
        state.setLastCheckpointAt(Instant.now());
        stateStore.write(properties.getPaths().getState(), state);

        ProcessingSummary summary = new ProcessingSummary();
        try {
            failedLog.readEach(properties.getPaths().getRetryInputs(), record -> {
                InventoryObject object = new InventoryObject(
                        properties.getS3().getSourceBucket(),
                        record.key(),
                        record.lastModified(),
                        record.size(),
                        record.eTag());
                summary.retried();
                summary.add(objectProcessor.process(object, JobMode.RETRY, runId, properties.getPaths().getRetryFailedLog()));
            });
            state.getCounters().add(summary.counters());
            state.setJobCompletedAt(Instant.now());
            state.setLastCheckpointAt(Instant.now());
            stateStore.write(properties.getPaths().getState(), state);
            log.info(
                    "Completed retry runId={} dryRun=false shard={}/{} summary={} counters={}",
                    runId,
                    properties.getShard().getIndex(),
                    properties.getShard().getTotal(),
                    summary.counters(),
                    state.getCounters());
        } catch (Exception exception) {
            recordRunFailure(state, "RETRY_ABORTED", exception);
            state.setLastCheckpointAt(Instant.now());
            stateStore.write(properties.getPaths().getState(), state);
            log.error("Retry aborted runId={} inputs={}", runId, properties.getPaths().getRetryInputs(), exception);
            throw exception;
        }
    }

    private void processManifestFiles(
            MigrationState state,
            String inventoryBucket,
            InventoryManifest manifest,
            JobMode mode,
            String runId,
            Instant watermark,
            ProcessingSummary total)
            throws Exception {
        processManifestFiles(state, inventoryBucket, manifest, mode, runId, watermark, total, true, null);
    }

    private void processManifestFiles(
            MigrationState state,
            String inventoryBucket,
            InventoryManifest manifest,
            JobMode mode,
            String runId,
            Instant watermark,
            ProcessingSummary total,
            boolean writeCheckpoints)
            throws Exception {
        processManifestFiles(state, inventoryBucket, manifest, mode, runId, watermark, total, writeCheckpoints, null);
    }

    private void processManifestFiles(
            MigrationState state,
            String inventoryBucket,
            InventoryManifest manifest,
            JobMode mode,
            String runId,
            Instant watermark,
            ProcessingSummary total,
            boolean writeCheckpoints,
            DryRunSampleLimiter dryRunSampleLimiter)
            throws Exception {
        ThreadPoolExecutor executor = newWorkerExecutor();
        try {
            long completedToSkip = writeCheckpoints ? state.completedFileCount(mode) : 0;
            long skippedCompleted = 0;
            String lastSkippedCompletedFile = null;
            for (InventoryDataFile file : manifest.files()) {
                if (skippedCompleted < completedToSkip) {
                    skippedCompleted++;
                    lastSkippedCompletedFile = file.key();
                    if (skippedCompleted == completedToSkip) {
                        validateCompletedFileCursor(state, mode, lastSkippedCompletedFile);
                    }
                    continue;
                }
                if (dryRunSampleLimiter != null && dryRunSampleLimiter.reached()) {
                    log.info(
                            "Upload dry-run sample size reached before next inventory data file mode={} runId={} nextKey={} sampledObjects={} dryRunSampleSize={} uploadDryRun=true",
                            mode,
                            runId,
                            file.key(),
                            dryRunSampleLimiter.submitted(),
                            dryRunSampleLimiter.limit());
                    break;
                }
                DataFileProcessingResult fileResult = processDataFile(
                        inventoryBucket, manifest.fileSchema(), file, mode, runId, watermark, executor, dryRunSampleLimiter);
                ProcessingSummary fileSummary = fileResult.summary();
                total.add(fileSummary);
                if (writeCheckpoints) {
                    state.markFileCompleted(mode, file.key());
                    state.getCounters().add(fileSummary.counters());
                    checkpointObservedWatermark(state, mode, watermark, total);
                    state.setLastCheckpointAt(Instant.now());
                    stateStore.write(properties.getPaths().getState(), state);
                }
                log.info(
                        "Processed inventory data file mode={} runId={} key={} summary={} scannedRows={} submittedRows={} observedMaxLastModified={} candidateWatermark={} stateCheckpointed={}",
                        mode,
                        runId,
                        file.key(),
                        fileSummary.counters(),
                        fileSummary.scannedRows(),
                        fileSummary.submittedRows(),
                        total.maxLastModified(),
                        state.getDeltaCandidateWatermark(),
                        writeCheckpoints);
                if (fileResult.sampleLimitReached()) {
                    log.info(
                            "Upload dry-run sample size reached inside inventory data file mode={} runId={} key={} sampledObjects={} dryRunSampleSize={} uploadDryRun=true",
                            mode,
                            runId,
                            file.key(),
                            dryRunSampleLimiter.submitted(),
                            dryRunSampleLimiter.limit());
                    break;
                }
            }
            if (skippedCompleted < completedToSkip) {
                throw new IllegalStateException("Stored " + mode + " completed file cursor is past manifest length: "
                        + completedToSkip + " completed files");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void validateCompletedFileCursor(MigrationState state, JobMode mode, String actualLastSkippedFile) {
        String expectedLastCompletedFile = state.lastCompletedFile(mode);
        if (expectedLastCompletedFile != null && !expectedLastCompletedFile.equals(actualLastSkippedFile)) {
            throw new IllegalStateException("Stored " + mode + " completed file cursor does not match manifest order: "
                    + "expected last completed file " + expectedLastCompletedFile
                    + " but skipped " + actualLastSkippedFile);
        }
    }

    private DataFileProcessingResult processDataFile(
            String inventoryBucket,
            String fileSchema,
            InventoryDataFile file,
            JobMode mode,
            String runId,
            Instant watermark,
            ThreadPoolExecutor executor,
            DryRunSampleLimiter dryRunSampleLimiter)
            throws Exception {
        ProcessingSummary summary = new ProcessingSummary();
        ExecutorCompletionService<ObjectProcessResult> completionService = new ExecutorCompletionService<>(executor);
        long[] inFlight = {0L};
        long maxInFlight = (long) properties.getWorker().getConcurrency() + properties.getWorker().getQueueSize();
        DataFileProgress progress =
                new DataFileProgress(Instant.now(), properties.getObservability().getProgressLogInterval());
        boolean sampleLimitReached = false;

        try (InputStream inputStream = objectReader.open(new S3Uri(inventoryBucket, file.key()))) {
            try {
                csvParser.parseGzip(
                        inputStream,
                        fileSchema,
                        object -> {
                            progress.scanned();
                            summary.scanned();
                            if (!properties.getS3().getSourceBucket().equals(object.bucket())) {
                                summary.skipped();
                                logDataFileProgressIfDue(mode, runId, file, progress, summary, inFlight[0]);
                                return;
                            }
                            if (mode == JobMode.DELTA && object.lastModified().isBefore(watermark)) {
                                summary.skipped();
                                logDataFileProgressIfDue(mode, runId, file, progress, summary, inFlight[0]);
                                return;
                            }
                            if (!shardAssigner.owns(
                                    object.key(), properties.getShard().getTotal(), properties.getShard().getIndex())) {
                                summary.skipped();
                                logDataFileProgressIfDue(mode, runId, file, progress, summary, inFlight[0]);
                                return;
                            }
                            if (dryRunSampleLimiter != null && !dryRunSampleLimiter.tryReserve()) {
                                summary.skipped();
                                logDataFileProgressIfDue(mode, runId, file, progress, summary, inFlight[0]);
                                throw DryRunSampleLimitReached.INSTANCE;
                            }
                            summary.observeLastModified(object.lastModified());
                            submit(
                                    completionService,
                                    () -> objectProcessor.process(
                                            object, mode, runId, properties.getPaths().getFailedLog()));
                            inFlight[0]++;
                            progress.submitted();
                            summary.submitted();
                            if (inFlight[0] >= maxInFlight) {
                                try {
                                    waitForOneCompletion(
                                            completionService, mode, runId, file, progress, summary, inFlight[0]);
                                } finally {
                                    inFlight[0]--;
                                }
                            }
                            logDataFileProgressIfDue(mode, runId, file, progress, summary, inFlight[0]);
                        },
                        failure -> {
                            progress.scanned();
                            summary.scanned();
                            handleRowParseFailure(file, mode, runId, summary, failure);
                            logDataFileProgressIfDue(mode, runId, file, progress, summary, inFlight[0]);
                        });
            } catch (DryRunSampleLimitReached exception) {
                sampleLimitReached = true;
            }
            while (inFlight[0] > 0) {
                try {
                    waitForOneCompletion(completionService, mode, runId, file, progress, summary, inFlight[0]);
                } finally {
                    inFlight[0]--;
                }
                logDataFileProgressIfDue(mode, runId, file, progress, summary, inFlight[0]);
            }
            return new DataFileProcessingResult(summary, sampleLimitReached);
        }
    }

    private ThreadPoolExecutor newWorkerExecutor() {
        int concurrency = properties.getWorker().getConcurrency();
        int queueSize = properties.getWorker().getQueueSize();
        return new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize),
                new BlockingRejectedExecutionHandler());
    }

    static class BlockingRejectedExecutionHandler implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable runnable, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                throw new RejectedExecutionException("Executor is shut down");
            }
            try {
                executor.getQueue().put(runnable);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("Interrupted while waiting for worker queue capacity", exception);
            }
        }
    }

    private void waitForOneCompletion(
            ExecutorCompletionService<ObjectProcessResult> completionService,
            JobMode mode,
            String runId,
            InventoryDataFile file,
            DataFileProgress progress,
            ProcessingSummary summary,
            long inFlight)
            throws Exception {
        if (!progress.isLoggingEnabled()) {
            summary.add(completionService.take().get());
            return;
        }
        while (true) {
            Future<ObjectProcessResult> future =
                    completionService.poll(progress.pollMillisUntilNextLog(Instant.now()), TimeUnit.MILLISECONDS);
            if (future != null) {
                summary.add(future.get());
                return;
            }
            logDataFileProgressIfDue(mode, runId, file, progress, summary, inFlight);
        }
    }

    private void handleRowParseFailure(
            InventoryDataFile file,
            JobMode mode,
            String runId,
            ProcessingSummary summary,
            InventoryRowParseFailure failure) {
        if (failure.rawBucket() != null && !properties.getS3().getSourceBucket().equals(failure.rawBucket())) {
            summary.skipped();
            return;
        }
        String key = parseFailureKey(file, failure);
        if (!ownsParseFailure(key)) {
            summary.skipped();
            return;
        }
        failedLog.append(
                properties.getPaths().getFailedLog(),
                FailedRecord.inventoryRowFailure(
                        runId,
                        mode,
                        properties.getShard().getTotal(),
                        properties.getShard().getIndex(),
                        properties.getS3().getSourceBucket(),
                        properties.getS3().getTargetBucket(),
                        key,
                        "INVENTORY_ROW_PARSE_FAILED",
                        "file=" + file.key() + ", recordNumber=" + failure.recordNumber() + ", error="
                                + failure.message()),
                properties.getObservability().getMaxFailedLogBytes());
        summary.add(ObjectProcessResult.FAILED);
    }

    private void logDataFileProgressIfDue(
            JobMode mode,
            String runId,
            InventoryDataFile file,
            DataFileProgress progress,
            ProcessingSummary summary,
            long inFlight) {
        Instant now = Instant.now();
        if (!progress.shouldLog(now)) {
            return;
        }
        log.info(
                "Processing inventory data file mode={} runId={} key={} elapsedSeconds={} scannedRows={} submittedRows={} inFlight={} summary={} observedMaxLastModified={}",
                mode,
                runId,
                file.key(),
                progress.elapsed(now).toSeconds(),
                progress.scannedRows(),
                progress.submittedRows(),
                inFlight,
                summary,
                summary.maxLastModified());
    }

    private String parseFailureKey(InventoryDataFile file, InventoryRowParseFailure failure) {
        if (failure.decodedKey() != null) {
            return failure.decodedKey();
        }
        if (failure.rawKey() != null) {
            return failure.rawKey();
        }
        return "inventory-row:" + file.key() + ":" + failure.recordNumber();
    }

    private boolean ownsParseFailure(String key) {
        if (key.startsWith("inventory-row:")) {
            return properties.getShard().getIndex() == 0;
        }
        return shardAssigner.owns(key, properties.getShard().getTotal(), properties.getShard().getIndex());
    }

    private static void submit(
            ExecutorCompletionService<ObjectProcessResult> completionService, Callable<ObjectProcessResult> callable) {
        completionService.submit(callable);
    }

    private void checkpointObservedWatermark(
            MigrationState state, JobMode mode, Instant previousWatermark, ProcessingSummary total) {
        if (mode == JobMode.BASELINE) {
            state.setBaselineObservedMaxLastModified(total.maxLastModified());
            state.setDeltaCandidateWatermark(watermarkPolicy.initialAfterBaseline(total));
            return;
        }
        if (mode == JobMode.DELTA) {
            state.setDeltaObservedMaxLastModified(total.maxLastModified());
            state.setDeltaCandidateWatermark(watermarkPolicy.advanceAfterDelta(previousWatermark, total));
        }
    }

    private static void clearLastError(MigrationState state) {
        state.setLastErrorCode(null);
        state.setLastErrorMessage(null);
        state.setLastErrorAt(null);
    }

    private static void recordRunFailure(MigrationState state, String code, Exception exception) {
        state.setLastErrorCode(code);
        state.setLastErrorMessage(
                exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        state.setLastErrorAt(Instant.now());
    }

    private String runId() {
        String configuredRunId = properties.getJob().getRunId();
        return configuredRunId == null || configuredRunId.isBlank() ? UUID.randomUUID().toString() : configuredRunId;
    }

    private MigrationState dryRunState() {
        MigrationState state = new MigrationState();
        state.setShardTotal(properties.getShard().getTotal());
        state.setShardIndex(properties.getShard().getIndex());
        state.setSourceBucket(properties.getS3().getSourceBucket());
        state.setTargetBucket(properties.getS3().getTargetBucket());
        return state;
    }

    private DryRunSampleLimiter dryRunSampleLimiter() {
        return new DryRunSampleLimiter(properties.getUpload().getDryRunSampleSize());
    }

    private record DataFileProcessingResult(ProcessingSummary summary, boolean sampleLimitReached) {}

    private static final class DryRunSampleLimiter {

        private final int limit;
        private int submitted;

        private DryRunSampleLimiter(int limit) {
            this.limit = limit;
        }

        private boolean tryReserve() {
            if (reached()) {
                return false;
            }
            submitted++;
            return true;
        }

        private boolean reached() {
            return submitted >= limit;
        }

        private int submitted() {
            return submitted;
        }

        private int limit() {
            return limit;
        }
    }

    private static final class DryRunSampleLimitReached extends RuntimeException {

        private static final DryRunSampleLimitReached INSTANCE = new DryRunSampleLimitReached();

        private DryRunSampleLimitReached() {
            super(null, null, false, false);
        }
    }
}
