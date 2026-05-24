package com.dailytools.s3migration.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.s3.AmazonS3Encryption;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;
import com.dailytools.s3migration.config.MigrationProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class ClientSideKmsTargetUploaderTest {

    @TempDir
    Path tempDir;

    @Test
    void uploadsSmallFileWithEncryptionClientPutObject() throws Exception {
        AmazonS3Encryption encryptedS3 = mock(AmazonS3Encryption.class);
        ClientSideKmsTargetUploader uploader =
                new ClientSideKmsTargetUploader(encryptedS3, uploadProperties(100, 5));
        Path localFile = writeFile("abc");

        uploader.upload("target-bucket", "folder/object.txt", localFile);

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(encryptedS3).putObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getBucketName()).isEqualTo("target-bucket");
        assertThat(requestCaptor.getValue().getKey()).isEqualTo("folder/object.txt");
        assertThat(requestCaptor.getValue().getFile()).isEqualTo(localFile.toFile());
        verify(encryptedS3, never()).initiateMultipartUpload(any(InitiateMultipartUploadRequest.class));
    }

    @Test
    void uploadsMultipartPartsInOrderAndCompletesWithReturnedEtags() throws Exception {
        AmazonS3Encryption encryptedS3 = mock(AmazonS3Encryption.class);
        stubMultipartStartAndParts(encryptedS3, "upload-1");
        ClientSideKmsTargetUploader uploader = new ClientSideKmsTargetUploader(encryptedS3, uploadProperties(5, 5));
        Path localFile = writeFile("abcdefghijkl");

        uploader.upload("target-bucket", "folder/object.bin", localFile);

        ArgumentCaptor<UploadPartRequest> partCaptor = ArgumentCaptor.forClass(UploadPartRequest.class);
        verify(encryptedS3, times(3)).uploadPart(partCaptor.capture());
        assertThat(partCaptor.getAllValues()).extracting(UploadPartRequest::getPartNumber).containsExactly(1, 2, 3);
        assertThat(partCaptor.getAllValues()).extracting(UploadPartRequest::getPartSize).containsExactly(5L, 5L, 2L);
        assertThat(partCaptor.getAllValues()).extracting(UploadPartRequest::getFileOffset).containsExactly(0L, 5L, 10L);
        assertThat(partCaptor.getAllValues()).extracting(UploadPartRequest::isLastPart).containsExactly(false, false, true);

        ArgumentCaptor<CompleteMultipartUploadRequest> completeCaptor =
                ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
        verify(encryptedS3).completeMultipartUpload(completeCaptor.capture());
        assertThat(completeCaptor.getValue().getBucketName()).isEqualTo("target-bucket");
        assertThat(completeCaptor.getValue().getKey()).isEqualTo("folder/object.bin");
        assertThat(completeCaptor.getValue().getUploadId()).isEqualTo("upload-1");
        assertThat(completeCaptor.getValue().getPartETags())
                .extracting(PartETag::getPartNumber)
                .containsExactly(1, 2, 3);
        assertThat(completeCaptor.getValue().getPartETags())
                .extracting(PartETag::getETag)
                .containsExactly("etag-1", "etag-2", "etag-3");
        verify(encryptedS3, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    }

    @Test
    void abortsMultipartUploadWhenCompleteFails() throws Exception {
        AmazonS3Encryption encryptedS3 = mock(AmazonS3Encryption.class);
        stubMultipartStartAndParts(encryptedS3, "upload-2");
        when(encryptedS3.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                .thenThrow(new RuntimeException("complete failed"));
        ClientSideKmsTargetUploader uploader = new ClientSideKmsTargetUploader(encryptedS3, uploadProperties(5, 3));
        Path localFile = writeFile("abcdef");

        assertThatThrownBy(() -> uploader.upload("target-bucket", "abort-me.bin", localFile))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Failed to client-side encrypt and upload target object target-bucket/abort-me.bin")
                .hasRootCauseMessage("complete failed");

        ArgumentCaptor<AbortMultipartUploadRequest> abortCaptor =
                ArgumentCaptor.forClass(AbortMultipartUploadRequest.class);
        verify(encryptedS3).abortMultipartUpload(abortCaptor.capture());
        assertThat(abortCaptor.getValue().getBucketName()).isEqualTo("target-bucket");
        assertThat(abortCaptor.getValue().getKey()).isEqualTo("abort-me.bin");
        assertThat(abortCaptor.getValue().getUploadId()).isEqualTo("upload-2");
    }

    @Test
    void wrapsLocalReadFailureWithTargetContext() {
        AmazonS3Encryption encryptedS3 = mock(AmazonS3Encryption.class);
        ClientSideKmsTargetUploader uploader =
                new ClientSideKmsTargetUploader(encryptedS3, uploadProperties(5, 3));

        assertThatThrownBy(() -> uploader.upload("target-bucket", "missing.bin", tempDir.resolve("missing.bin")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(
                        "Failed to client-side encrypt and upload target object target-bucket/missing.bin");
    }

    @Test
    void preservesOriginalMultipartFailureWhenAbortFails() throws Exception {
        AmazonS3Encryption encryptedS3 = mock(AmazonS3Encryption.class);
        stubMultipartStartAndParts(encryptedS3, "upload-3");
        when(encryptedS3.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                .thenThrow(new RuntimeException("complete failed"));
        doThrow(new RuntimeException("abort failed"))
                .when(encryptedS3)
                .abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        ClientSideKmsTargetUploader uploader =
                new ClientSideKmsTargetUploader(encryptedS3, uploadProperties(5, 3));
        Path localFile = writeFile("abcdef");

        Throwable thrown = catchThrowable(() -> uploader.upload("target-bucket", "abort-fails.bin", localFile));

        assertThat(thrown)
                .isInstanceOf(IOException.class)
                .hasMessageContaining(
                        "Failed to client-side encrypt and upload target object target-bucket/abort-fails.bin")
                .hasRootCauseMessage("complete failed");
        assertThat(thrown.getCause().getSuppressed()).extracting(Throwable::getMessage).containsExactly("abort failed");
    }

    private static void stubMultipartStartAndParts(AmazonS3Encryption encryptedS3, String uploadId) {
        InitiateMultipartUploadResult startResult = new InitiateMultipartUploadResult();
        startResult.setUploadId(uploadId);
        when(encryptedS3.initiateMultipartUpload(any(InitiateMultipartUploadRequest.class))).thenReturn(startResult);
        when(encryptedS3.uploadPart(any(UploadPartRequest.class))).thenAnswer(invocation -> {
            UploadPartRequest request = invocation.getArgument(0);
            UploadPartResult result = new UploadPartResult();
            result.setPartNumber(request.getPartNumber());
            result.setETag("etag-" + request.getPartNumber());
            return result;
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
