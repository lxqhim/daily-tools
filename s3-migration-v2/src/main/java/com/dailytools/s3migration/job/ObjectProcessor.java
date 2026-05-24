package com.dailytools.s3migration.job;

import com.dailytools.s3migration.config.MigrationProperties;
import com.dailytools.s3migration.decrypt.S3ObjectDecryptor;
import com.dailytools.s3migration.failed.FailedLog;
import com.dailytools.s3migration.failed.FailedRecord;
import com.dailytools.s3migration.inventory.InventoryObject;
import com.dailytools.s3migration.upload.TargetUploader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ObjectProcessor {

    private static final Logger log = LoggerFactory.getLogger(ObjectProcessor.class);

    private final S3ObjectDecryptor decryptor;
    private final TargetUploader uploader;
    private final FailedLog failedLog;
    private final MigrationProperties properties;

    public ObjectProcessor(
            S3ObjectDecryptor decryptor,
            TargetUploader uploader,
            FailedLog failedLog,
            MigrationProperties properties) {
        this.decryptor = decryptor;
        this.uploader = uploader;
        this.failedLog = failedLog;
        this.properties = properties;
    }

    public ObjectProcessResult process(InventoryObject object, JobMode mode, String runId, Path failedLogPath) {
        Path localFile = null;
        Exception operationFailure = null;
        Exception cleanupFailure = null;
        try {
            localFile = decryptor.decryptToLocal(properties.getS3().getSourceBucket(), object.key());
            if (localFile == null) {
                throw new IllegalStateException("Decryptor returned null local file path");
            }
            if (properties.getUpload().isDryRun()) {
                logUploadDryRun(object, localFile);
            } else {
                uploader.upload(properties.getS3().getTargetBucket(), object.key(), localFile);
            }
        } catch (Exception exception) {
            operationFailure = exception;
        } finally {
            boolean successfulDryRun = operationFailure == null && properties.getUpload().isDryRun();
            if (localFile != null && !successfulDryRun) {
                try {
                    Files.deleteIfExists(localFile);
                } catch (Exception exception) {
                    cleanupFailure = exception;
                }
            }
        }

        if (operationFailure != null) {
            if (cleanupFailure != null) {
                operationFailure.addSuppressed(cleanupFailure);
            }
            appendFailure(failedLogPath, object, mode, runId, "OBJECT_PROCESSING_FAILED", operationFailure);
            return ObjectProcessResult.FAILED;
        }
        if (cleanupFailure != null) {
            log.warn(
                    "Failed to delete local temp file after successful object processing sourceBucket={} targetBucket={} key={} localFile={}",
                    properties.getS3().getSourceBucket(),
                    properties.getS3().getTargetBucket(),
                    object.key(),
                    localFile,
                    cleanupFailure);
        }
        return properties.getUpload().isDryRun() ? ObjectProcessResult.DRY_RUN_SUCCESS : ObjectProcessResult.SUCCESS;
    }

    private void logUploadDryRun(InventoryObject object, Path localFile) throws Exception {
        log.info(
                "Upload dry-run enabled; decrypted object sourceBucket={} targetBucket={} key={} localFile={} sizeBytes={} uploadSkipped=true localFileRetained=true",
                properties.getS3().getSourceBucket(),
                properties.getS3().getTargetBucket(),
                object.key(),
                localFile,
                Files.size(localFile));
    }

    private void appendFailure(
            Path failedLogPath, InventoryObject object, JobMode mode, String runId, String code, Exception exception) {
        failedLog.append(
                failedLogPath,
                FailedRecord.of(
                        runId,
                        mode,
                        properties.getShard().getTotal(),
                        properties.getShard().getIndex(),
                        properties.getS3().getSourceBucket(),
                        properties.getS3().getTargetBucket(),
                        object,
                        code,
                        exceptionSummary(exception)),
                properties.getObservability().getMaxFailedLogBytes());
    }

    private static String exceptionSummary(Throwable throwable) {
        StringBuilder summary = new StringBuilder();
        appendExceptionSummary(summary, throwable);
        return summary.toString();
    }

    private static void appendExceptionSummary(StringBuilder summary, Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (!summary.isEmpty()) {
                summary.append(" | caused by: ");
            }
            summary.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                summary.append(": ").append(message);
            }
            current = current.getCause();
        }
        for (Throwable suppressed : throwable.getSuppressed()) {
            if (!summary.isEmpty()) {
                summary.append(" | suppressed: ");
            }
            appendExceptionSummary(summary, suppressed);
        }
    }
}
