package com.dailytools.s3migration.batchlambda;

interface BatchTaskProcessor {

    S3BatchTaskResult process(S3BatchTask task);
}
