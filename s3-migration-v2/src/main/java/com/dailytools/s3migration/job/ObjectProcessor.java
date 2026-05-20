package com.dailytools.s3migration.job;

import com.dailytools.s3migration.config.MigrationProperties;
import com.dailytools.s3migration.decrypt.S3ObjectDecryptor;
import com.dailytools.s3migration.failed.FailedLog;
import com.dailytools.s3migration.failed.FailedRecord;
import com.dailytools.s3migration.inventory.InventoryObject;
import com.dailytools.s3migration.upload.TargetUploader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class ObjectProcessor {

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
            uploader.upload(properties.getS3().getTargetBucket(), object.key(), localFile);
        } catch (Exception exception) {
            operationFailure = exception;
        } finally {
            if (localFile != null) {
                try {
                    Files.deleteIfExists(localFile);
                } catch (Exception exception) {
                    cleanupFailure = exception;
                }
            }
        }

        if (operationFailure != null) {
            appendFailure(failedLogPath, object, mode, runId, "OBJECT_PROCESSING_FAILED", operationFailure);
            return ObjectProcessResult.FAILED;
        }
        if (cleanupFailure != null) {
            appendFailure(failedLogPath, object, mode, runId, "LOCAL_CLEANUP_FAILED", cleanupFailure);
            return ObjectProcessResult.FAILED;
        }
        return ObjectProcessResult.SUCCESS;
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
                        exception.getMessage()),
                properties.getObservability().getMaxFailedLogBytes());
    }
}
