package com.dailytools.s3migration.upload;

import com.dailytools.s3migration.config.MigrationProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

public class S3TargetUploader implements TargetUploader {

    private final S3Client s3Client;
    private final MigrationProperties.Upload uploadProperties;

    public S3TargetUploader(S3Client s3Client, MigrationProperties.Upload uploadProperties) {
        this.s3Client = s3Client;
        this.uploadProperties = uploadProperties;
    }

    @Override
    public void upload(String bucketName, String key, Path localFile) throws IOException {
        try {
            long size = Files.size(localFile);
            if (size < uploadProperties.getMultipartThresholdBytes()) {
                putObject(bucketName, key, localFile);
            } else {
                multipartUpload(bucketName, key, localFile);
            }
        } catch (RuntimeException | IOException exception) {
            throw new IOException("Failed to upload target object " + bucketName + "/" + key, exception);
        }
    }

    private void putObject(String bucketName, String key, Path localFile) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
        s3Client.putObject(request, RequestBody.fromFile(localFile));
    }

    private void multipartUpload(String bucketName, String key, Path localFile) throws IOException {
        CreateMultipartUploadResponse createResponse = s3Client.createMultipartUpload(
                CreateMultipartUploadRequest.builder().bucket(bucketName).key(key).build());
        String uploadId = createResponse.uploadId();
        List<CompletedPart> completedParts = new ArrayList<>();
        try (InputStream inputStream = Files.newInputStream(localFile)) {
            int partNumber = 1;
            int partSize = uploadProperties.getMultipartPartSizeBytes();
            byte[] buffer = new byte[partSize];
            int read;
            while ((read = readPart(inputStream, buffer)) > 0) {
                byte[] payload = read == buffer.length ? buffer : Arrays.copyOf(buffer, read);
                UploadPartRequest request = UploadPartRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .uploadId(uploadId)
                        .partNumber(partNumber)
                        .contentLength((long) read)
                        .build();
                String eTag = s3Client.uploadPart(request, RequestBody.fromBytes(payload)).eTag();
                completedParts.add(CompletedPart.builder().partNumber(partNumber).eTag(eTag).build());
                partNumber++;
            }
            CompletedMultipartUpload completedMultipartUpload =
                    CompletedMultipartUpload.builder().parts(completedParts).build();
            s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(completedMultipartUpload)
                    .build());
        } catch (RuntimeException | IOException exception) {
            try {
                s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .uploadId(uploadId)
                        .build());
            } catch (Exception abortException) {
                exception.addSuppressed(abortException);
            }
            throw exception;
        }
    }

    private static int readPart(InputStream inputStream, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = inputStream.read(buffer, offset, buffer.length - offset);
            if (read == -1) {
                break;
            }
            offset += read;
        }
        return offset;
    }
}
