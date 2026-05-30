package com.dailytools.s3migration.inventory;

import java.time.Instant;

public record InventoryObject(String bucket, String key, Instant lastModified, long size, String eTag) {}
