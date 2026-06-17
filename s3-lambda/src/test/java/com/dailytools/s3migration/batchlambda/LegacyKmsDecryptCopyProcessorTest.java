package com.dailytools.s3migration.batchlambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Encryption;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class LegacyKmsDecryptCopyProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void decryptsVersionedSourceObjectAndUploadsDecodedKeyToTarget() throws Exception {
        AmazonS3Encryption sourceClient = mock(AmazonS3Encryption.class);
        AmazonS3 targetClient = mock(AmazonS3.class);
        when(sourceClient.getObject(any(GetObjectRequest.class), any(File.class))).thenAnswer(invocation -> {
            File destination = invocation.getArgument(1);
            Files.writeString(destination.toPath(), "plain-text");
            return new ObjectMetadata();
        });
        BatchLambdaConfig config = config(Map.of("TARGET_KEY_PREFIX", "copied"));
        LegacyKmsDecryptCopyProcessor processor =
                new LegacyKmsDecryptCopyProcessor(sourceClient, targetClient, config);
        S3BatchTask task = task("task-1", "arn:aws:s3:::source-bucket", "folder/a%20b%2Bc.txt", "version-1");

        S3BatchTaskResult result = processor.process(task);

        assertThat(result.getResultCode()).isEqualTo("Succeeded");
        ArgumentCaptor<GetObjectRequest> getCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(sourceClient).getObject(getCaptor.capture(), any(File.class));
        assertThat(getCaptor.getValue().getBucketName()).isEqualTo("source-bucket");
        assertThat(getCaptor.getValue().getKey()).isEqualTo("folder/a b+c.txt");
        assertThat(getCaptor.getValue().getVersionId()).isEqualTo("version-1");

        ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(targetClient).putObject(putCaptor.capture());
        assertThat(putCaptor.getValue().getBucketName()).isEqualTo("target-bucket");
        assertThat(putCaptor.getValue().getKey()).isEqualTo("copied/folder/a b+c.txt");
        assertThat(putCaptor.getValue().getFile()).doesNotExist();
    }

    @Test
    void marksThrottlingAsTemporaryFailure() {
        AmazonS3Encryption sourceClient = mock(AmazonS3Encryption.class);
        AmazonS3 targetClient = mock(AmazonS3.class);
        AmazonServiceException throttled = new AmazonServiceException("rate exceeded");
        throttled.setStatusCode(503);
        throttled.setErrorCode("SlowDown");
        when(sourceClient.getObject(any(GetObjectRequest.class), any(File.class))).thenThrow(throttled);
        LegacyKmsDecryptCopyProcessor processor =
                new LegacyKmsDecryptCopyProcessor(sourceClient, targetClient, config(Map.of()));

        S3BatchTaskResult result =
                processor.process(task("task-1", "arn:aws:s3:::source-bucket", "folder/file.txt", null));

        assertThat(result.getResultCode()).isEqualTo("TemporaryFailure");
        assertThat(result.getResultString()).contains("SlowDown");
    }

    @Test
    void marksInvalidTaskAsPermanentFailure() {
        LegacyKmsDecryptCopyProcessor processor =
                new LegacyKmsDecryptCopyProcessor(mock(AmazonS3Encryption.class), mock(AmazonS3.class), config(Map.of()));

        S3BatchTaskResult result = processor.process(task("task-1", "not-an-arn", "", null));

        assertThat(result.getResultCode()).isEqualTo("PermanentFailure");
        assertThat(result.getResultString()).contains("Task S3 key is required");
    }

    @Test
    void parsesBucketNameFromArnAndPlainName() {
        assertThat(LegacyKmsDecryptCopyProcessor.bucketNameFromArn("arn:aws:s3:::bucket-name"))
                .isEqualTo("bucket-name");
        assertThat(LegacyKmsDecryptCopyProcessor.bucketNameFromArn("bucket-name"))
                .isEqualTo("bucket-name");
    }

    private BatchLambdaConfig config(Map<String, String> overrides) {
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("AWS_REGION", "us-east-1");
        env.put("TARGET_BUCKET", "target-bucket");
        env.put("SOURCE_KMS_KEY_ID", "arn:aws:kms:us-east-1:111122223333:key/source-key");
        env.put("TEMP_DIR", tempDir.toString());
        env.putAll(overrides);
        return BatchLambdaConfig.from(env);
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
