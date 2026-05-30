package com.dailytools.s3migration.inventory;

public record InventoryDataFile(String key, long size, String md5Checksum) {}
