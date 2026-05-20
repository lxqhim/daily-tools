package com.dailytools.s3migration.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dailytools.s3migration.config.MigrationProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

class S3TargetUploaderTest {

    @TempDir
    Path tempDir;

    @Test
    void uploadsMultipartPartsInOrderAndCompletesWithReturnedEtags() throws Exception {
        S3Client s3Client = mock(S3Client.class);
        stubMultipartStartAndParts(s3Client, "upload-1");
        S3TargetUploader uploader = new S3TargetUploader(s3Client, uploadProperties(5, 5));
        Path localFile = writeFile("abcdefghijkl");

        uploader.upload("target-bucket", "folder/object.bin", localFile);

        ArgumentCaptor<UploadPartRequest> partCaptor = ArgumentCaptor.forClass(UploadPartRequest.class);
        verify(s3Client, times(3)).uploadPart(partCaptor.capture(), any(RequestBody.class));
        assertThat(partCaptor.getAllValues()).extracting(UploadPartRequest::partNumber).containsExactly(1, 2, 3);
        assertThat(partCaptor.getAllValues()).extracting(UploadPartRequest::contentLength).containsExactly(5L, 5L, 2L);

        ArgumentCaptor<CompleteMultipartUploadRequest> completeCaptor =
                ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
        verify(s3Client).completeMultipartUpload(completeCaptor.capture());
        assertThat(completeCaptor.getValue().bucket()).isEqualTo("target-bucket");
        assertThat(completeCaptor.getValue().key()).isEqualTo("folder/object.bin");
        assertThat(completeCaptor.getValue().uploadId()).isEqualTo("upload-1");
        assertThat(completeCaptor.getValue().multipartUpload().parts())
                .extracting(CompletedPart::partNumber)
                .containsExactly(1, 2, 3);
        assertThat(completeCaptor.getValue().multipartUpload().parts())
                .extracting(CompletedPart::eTag)
                .containsExactly("etag-1", "etag-2", "etag-3");
        verify(s3Client, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    }

    @Test
    void doesNotUploadEmptyPartWhenFileEndsOnPartBoundary() throws Exception {
        S3Client s3Client = mock(S3Client.class);
        stubMultipartStartAndParts(s3Client, "upload-2");
        S3TargetUploader uploader = new S3TargetUploader(s3Client, uploadProperties(5, 5));
        Path localFile = writeFile("abcdefghij");

        uploader.upload("target-bucket", "exact-boundary.bin", localFile);

        ArgumentCaptor<UploadPartRequest> partCaptor = ArgumentCaptor.forClass(UploadPartRequest.class);
        verify(s3Client, times(2)).uploadPart(partCaptor.capture(), any(RequestBody.class));
        assertThat(partCaptor.getAllValues()).extracting(UploadPartRequest::partNumber).containsExactly(1, 2);
        assertThat(partCaptor.getAllValues()).extracting(UploadPartRequest::contentLength).containsExactly(5L, 5L);
    }

    @Test
    void abortsMultipartUploadWhenCompleteFails() throws Exception {
        S3Client s3Client = mock(S3Client.class);
        stubMultipartStartAndParts(s3Client, "upload-3");
        when(s3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                .thenThrow(new RuntimeException("complete failed"));
        S3TargetUploader uploader = new S3TargetUploader(s3Client, uploadProperties(5, 3));
        Path localFile = writeFile("abcdef");

        assertThatThrownBy(() -> uploader.upload("target-bucket", "abort-me.bin", localFile))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Failed to upload target object target-bucket/abort-me.bin")
                .hasRootCauseMessage("complete failed");

        ArgumentCaptor<AbortMultipartUploadRequest> abortCaptor =
                ArgumentCaptor.forClass(AbortMultipartUploadRequest.class);
        verify(s3Client).abortMultipartUpload(abortCaptor.capture());
        assertThat(abortCaptor.getValue().bucket()).isEqualTo("target-bucket");
        assertThat(abortCaptor.getValue().key()).isEqualTo("abort-me.bin");
        assertThat(abortCaptor.getValue().uploadId()).isEqualTo("upload-3");
    }

    private static void stubMultipartStartAndParts(S3Client s3Client, String uploadId) {
        when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId(uploadId).build());
        when(s3Client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    UploadPartRequest request = invocation.getArgument(0);
                    return UploadPartResponse.builder()
                            .eTag("etag-" + request.partNumber())
                            .build();
                });
    }

    private static MigrationProperties.Upload uploadProperties(long thresholdBytes, int partSizeBytes) {
        MigrationProperties.Upload upload = new MigrationProperties.Upload();
        upload.setMultipartThresholdBytes(thresholdBytes);
        upload.setMultipartPartSizeBytes(partSizeBytes);
        return upload;
    }

    private Path writeFile(String value) throws IOException {
        Path path = tempDir.resolve("object.bin");
        Files.writeString(path, value);
        return path;
    }
}
