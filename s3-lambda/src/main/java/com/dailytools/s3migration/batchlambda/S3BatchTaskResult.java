package com.dailytools.s3migration.batchlambda;

final class S3BatchTaskResult {

    private String taskId;
    private String resultCode;
    private String resultString;

    S3BatchTaskResult() {}

    S3BatchTaskResult(String taskId, BatchResultCode resultCode, String resultString) {
        this.taskId = taskId;
        this.resultCode = resultCode.value();
        this.resultString = resultString;
    }

    String getTaskId() {
        return taskId;
    }

    void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    String getResultCode() {
        return resultCode;
    }

    void setResultCode(String resultCode) {
        this.resultCode = resultCode;
    }

    String getResultString() {
        return resultString;
    }

    void setResultString(String resultString) {
        this.resultString = resultString;
    }
}
