package com.dailytools.s3migration.failed;

import com.dailytools.s3migration.inventory.InventoryObject;
import com.dailytools.s3migration.job.JobMode;
import java.time.Instant;

public record FailedRecord(
        String runId,
        JobMode mode,
        int shardTotal,
        int shardIndex,
        String sourceBucket,
        String targetBucket,
        String key,
        Instant lastModified,
        String eTag,
        long size,
        String errorCode,
        String message,
        Instant timestamp) {

    public static FailedRecord of(
            String runId,
            JobMode mode,
            int shardTotal,
            int shardIndex,
            String sourceBucket,
            String targetBucket,
            InventoryObject object,
            String errorCode,
            String message) {
        return new FailedRecord(
                runId,
                mode,
                shardTotal,
                shardIndex,
                sourceBucket,
                targetBucket,
                object.key(),
                object.lastModified(),
                object.eTag(),
                object.size(),
                errorCode,
                message,
                Instant.now());
    }

    public static FailedRecord inventoryRowFailure(
            String runId,
            JobMode mode,
            int shardTotal,
            int shardIndex,
            String sourceBucket,
            String targetBucket,
            String key,
            String errorCode,
            String message) {
        return new FailedRecord(
                runId,
                mode,
                shardTotal,
                shardIndex,
                sourceBucket,
                targetBucket,
                key,
                null,
                null,
                -1L,
                errorCode,
                message,
                Instant.now());
    }
}
