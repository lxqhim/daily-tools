package com.dailytools.s3migration.upload;

import com.amazonaws.services.s3.AmazonS3Encryption;
import com.amazonaws.services.s3.AmazonS3EncryptionClientBuilder;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.KMSEncryptionMaterialsProvider;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.dailytools.s3migration.config.MigrationProperties;
import com.dailytools.s3migration.crypto.AwsCryptoConfigurationFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ClientSideKmsTargetUploader implements TargetUploader {

    private final AmazonS3Encryption encryptedS3;
    private final MigrationProperties.Upload uploadProperties;

    public ClientSideKmsTargetUploader(MigrationProperties properties) {
        this(encryptedS3(properties), properties.getUpload());
    }

    ClientSideKmsTargetUploader(AmazonS3Encryption encryptedS3, MigrationProperties.Upload uploadProperties) {
        this.encryptedS3 = encryptedS3;
        this.uploadProperties = uploadProperties;
    }

    @Override
    public void upload(String bucketName, String key, Path localFile) throws IOException {
        try {
            long size = Files.size(localFile);
            if (size < uploadProperties.getMultipartThresholdBytes()) {
                putObject(bucketName, key, localFile);
            } else {
                multipartUpload(bucketName, key, localFile, size);
            }
        } catch (RuntimeException | IOException exception) {
            throw new IOException("Failed to client-side encrypt and upload target object " + bucketName + "/" + key,
                    exception);
        }
    }

    private void putObject(String bucketName, String key, Path localFile) {
        encryptedS3.putObject(new PutObjectRequest(bucketName, key, localFile.toFile()));
    }

    private void multipartUpload(String bucketName, String key, Path localFile, long size) {
        String uploadId = encryptedS3
                .initiateMultipartUpload(new InitiateMultipartUploadRequest(bucketName, key))
                .getUploadId();
        List<PartETag> partETags = new ArrayList<>();
        try {
            long offset = 0;
            int partNumber = 1;
            int partSize = uploadProperties.getMultipartPartSizeBytes();
            while (offset < size) {
                long currentPartSize = Math.min(partSize, size - offset);
                UploadPartRequest request = new UploadPartRequest()
                        .withBucketName(bucketName)
                        .withKey(key)
                        .withUploadId(uploadId)
                        .withPartNumber(partNumber)
                        .withFile(localFile.toFile())
                        .withFileOffset(offset)
                        .withPartSize(currentPartSize)
                        .withLastPart(offset + currentPartSize >= size);
                partETags.add(encryptedS3.uploadPart(request).getPartETag());
                offset += currentPartSize;
                partNumber++;
            }
            encryptedS3.completeMultipartUpload(new CompleteMultipartUploadRequest(bucketName, key, uploadId, partETags));
        } catch (RuntimeException exception) {
            try {
                encryptedS3.abortMultipartUpload(new AbortMultipartUploadRequest(bucketName, key, uploadId));
            } catch (Exception abortException) {
                exception.addSuppressed(abortException);
            }
            throw exception;
        }
    }

    private static AmazonS3Encryption encryptedS3(MigrationProperties properties) {
        MigrationProperties.ClientSideKms clientSideKms = properties.getUpload().getClientSideKms();
        return AmazonS3EncryptionClientBuilder.standard()
                .withRegion(properties.getS3().getRegion())
                .withCryptoConfiguration(AwsCryptoConfigurationFactory.create(
                        clientSideKms.getCryptoMode(), clientSideKms.getStorageMode(), kmsRegion(properties)))
                .withEncryptionMaterials(new KMSEncryptionMaterialsProvider(clientSideKms.getKmsKeyId()))
                .build();
    }

    private static String kmsRegion(MigrationProperties properties) {
        String configuredKmsRegion = properties.getUpload().getClientSideKms().getKmsRegion();
        if (configuredKmsRegion != null && !configuredKmsRegion.isBlank()) {
            return configuredKmsRegion;
        }
        return properties.getS3().getRegion();
    }
}
