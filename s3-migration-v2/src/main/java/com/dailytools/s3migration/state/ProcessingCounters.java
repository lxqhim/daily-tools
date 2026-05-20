package com.dailytools.s3migration.state;

public record ProcessingCounters(long success, long failed, long skipped, long retried) {}
