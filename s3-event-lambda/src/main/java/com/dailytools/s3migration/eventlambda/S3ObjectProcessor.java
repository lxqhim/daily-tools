package com.dailytools.s3migration.eventlambda;

@FunctionalInterface
interface S3ObjectProcessor {

    void process(S3ObjectTask task) throws Exception;
}
