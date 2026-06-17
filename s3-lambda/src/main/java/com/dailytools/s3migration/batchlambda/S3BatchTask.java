package com.dailytools.s3migration.batchlambda;

final class S3BatchTask {

    private String taskId;
    private String s3BucketArn;
    private String s3Key;
    private String s3VersionId;

    String getTaskId() {
        return taskId;
    }

    void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    String getS3BucketArn() {
        return s3BucketArn;
    }

    void setS3BucketArn(String s3BucketArn) {
        this.s3BucketArn = s3BucketArn;
    }

    String getS3Key() {
        return s3Key;
    }

    void setS3Key(String s3Key) {
        this.s3Key = s3Key;
    }

    String getS3VersionId() {
        return s3VersionId;
    }

    void setS3VersionId(String s3VersionId) {
        this.s3VersionId = s3VersionId;
    }
}
