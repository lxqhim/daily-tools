package com.dailytools.s3migration.inventory;

import java.time.Instant;
import java.util.List;

public record InventoryManifest(String fileSchema, Instant creationTimestamp, List<InventoryDataFile> files) {}
