package com.dailytools.s3migration.batchlambda;

import java.util.ArrayList;
import java.util.List;

final class S3BatchLambdaResponse {

    private String invocationSchemaVersion;
    private String invocationId;
    private String treatMissingKeysAs = BatchResultCode.TEMPORARY_FAILURE.value();
    private List<S3BatchTaskResult> results = new ArrayList<>();

    S3BatchLambdaResponse() {}

    S3BatchLambdaResponse(String invocationSchemaVersion, String invocationId, List<S3BatchTaskResult> results) {
        this.invocationSchemaVersion = invocationSchemaVersion;
        this.invocationId = invocationId;
        this.results = results;
    }

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

    String getTreatMissingKeysAs() {
        return treatMissingKeysAs;
    }

    void setTreatMissingKeysAs(String treatMissingKeysAs) {
        this.treatMissingKeysAs = treatMissingKeysAs;
    }

    List<S3BatchTaskResult> getResults() {
        return results;
    }

    void setResults(List<S3BatchTaskResult> results) {
        this.results = results == null ? new ArrayList<>() : results;
    }
}
