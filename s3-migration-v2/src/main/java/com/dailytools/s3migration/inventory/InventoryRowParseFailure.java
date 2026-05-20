package com.dailytools.s3migration.inventory;

public record InventoryRowParseFailure(
        long recordNumber,
        String rawBucket,
        String rawKey,
        String decodedKey,
        String rawLastModified,
        String rawSize,
        String rawETag,
        String message,
        Throwable cause) {}
