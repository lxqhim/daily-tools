package com.dailytools.s3migration.batchlambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.CopyObjectRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class S3KeyRenameProcessorTest {

    @Test
    void copiesVersionedObjectToRenamedKeyInTheSameBucket() {
        AmazonS3 client = mock(AmazonS3.class);
        S3KeyRenameProcessor processor = new S3KeyRenameProcessor(client, config(Map.of()));

        S3BatchTaskResult result = processor.process(task("task-1", "arn:aws:s3:::bucket", "HK/ABC/BAR/a%20b.txt", "v1"));

        assertThat(result.getResultCode()).isEqualTo("Succeeded");
        ArgumentCaptor<CopyObjectRequest> request = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(client).copyObject(request.capture());
        assertThat(request.getValue().getSourceBucketName()).isEqualTo("bucket");
        assertThat(request.getValue().getSourceKey()).isEqualTo("HK/ABC/BAR/a b.txt");
        assertThat(request.getValue().getDestinationBucketName()).isEqualTo("bucket");
        assertThat(request.getValue().getDestinationKey()).isEqualTo("HK/ABC/BAR_PRINT/a b.txt");
        assertThat(request.getValue().getSourceVersionId()).isEqualTo("v1");
    }

    @Test
    void supportsAnyRootSegmentWithTheConfiguredSourceSegment() {
        AmazonS3 client = mock(AmazonS3.class);
        S3KeyRenameProcessor processor = new S3KeyRenameProcessor(client, config(Map.of()));

        S3BatchTaskResult result = processor.process(task("task-1", "arn:aws:s3:::bucket", "CN/ABC/BAR/file.txt", null));

        assertThat(result.getResultCode()).isEqualTo("Succeeded");
        ArgumentCaptor<CopyObjectRequest> request = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(client).copyObject(request.capture());
        assertThat(request.getValue().getDestinationKey()).isEqualTo("CN/ABC/BAR_PRINT/file.txt");
    }

    @Test
    void replacesOnlyTheConfiguredSourceSegmentIndex() {
        AmazonS3 client = mock(AmazonS3.class);
        S3KeyRenameProcessor processor = new S3KeyRenameProcessor(client, config(Map.of("RENAME_SOURCE_SEGMENT_INDEX", "4")));

        S3BatchTaskResult result = processor.process(task("task-1", "arn:aws:s3:::bucket", "HK/BAR/XXX/BAR/file.txt", null));

        assertThat(result.getResultCode()).isEqualTo("Succeeded");
        ArgumentCaptor<CopyObjectRequest> request = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(client).copyObject(request.capture());
        assertThat(request.getValue().getDestinationKey()).isEqualTo("HK/BAR/XXX/BAR_PRINT/file.txt");
    }

    @Test
    void rejectsKeyWithoutTheConfiguredSourceSegmentAtTheConfiguredIndexWithoutCopying() {
        AmazonS3 client = mock(AmazonS3.class);
        S3KeyRenameProcessor processor = new S3KeyRenameProcessor(client, config(Map.of()));

        S3BatchTaskResult result = processor.process(task("task-1", "arn:aws:s3:::bucket", "HK/ABC/OTHER/file.txt", null));

        assertThat(result.getResultCode()).isEqualTo("PermanentFailure");
        assertThat(result.getResultString()).contains("BAR at segment 3");
        verify(client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void marksS3ThrottlingAsTemporaryFailure() {
        AmazonS3 client = mock(AmazonS3.class);
        AmazonServiceException throttled = new AmazonServiceException("rate exceeded");
        throttled.setStatusCode(503);
        throttled.setErrorCode("SlowDown");
        when(client.copyObject(any(CopyObjectRequest.class))).thenThrow(throttled);
        S3KeyRenameProcessor processor = new S3KeyRenameProcessor(client, config(Map.of()));

        S3BatchTaskResult result = processor.process(task("task-1", "arn:aws:s3:::bucket", "HK/ABC/BAR/file.txt", null));

        assertThat(result.getResultCode()).isEqualTo("TemporaryFailure");
    }

    private static KeyRenameConfig config(Map<String, String> overrides) {
        Map<String, String> environment = new java.util.HashMap<>();
        environment.put("AWS_REGION", "us-east-1");
        environment.put("RENAME_SOURCE_SEGMENT", "BAR");
        environment.put("RENAME_TARGET_SEGMENT", "BAR_PRINT");
        environment.putAll(overrides);
        return KeyRenameConfig.from(environment);
    }

    private static S3BatchTask task(String taskId, String bucketArn, String key, String versionId) {
        S3BatchTask task = new S3BatchTask();
        task.setTaskId(taskId);
        task.setS3BucketArn(bucketArn);
        task.setS3Key(key);
        task.setS3VersionId(versionId);
        return task;
    }
}
