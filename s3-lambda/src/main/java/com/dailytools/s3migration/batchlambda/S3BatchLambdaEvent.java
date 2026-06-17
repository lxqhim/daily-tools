package com.dailytools.s3migration.batchlambda;

import java.util.ArrayList;
import java.util.List;

final class S3BatchLambdaEvent {

    private String invocationSchemaVersion;
    private String invocationId;
    private List<S3BatchTask> tasks = new ArrayList<>();

    String getInvocationSchemaVersion() {
        return invocationSchemaVersion;
    }

    void setInvocationSchemaVersion(String invocationSchemaVersion) {
        this.invocationSchemaVersion = invocationSchemaVersion;
    }

    String getInvocationId() {
        return invocationId;
    }

    void setInvocationId(String invocationId) {
        this.invocationId = invocationId;
    }

    List<S3BatchTask> getTasks() {
        return tasks;
    }

    void setTasks(List<S3BatchTask> tasks) {
        this.tasks = tasks == null ? new ArrayList<>() : tasks;
    }
}
