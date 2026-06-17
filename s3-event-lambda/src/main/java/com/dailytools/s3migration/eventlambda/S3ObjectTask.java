package com.dailytools.s3migration.eventlambda;

record S3ObjectTask(String sourceBucket, String sourceKey, String versionId) {}
