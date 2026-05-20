package com.dailytools.s3migration.decrypt;

import java.nio.file.Path;

public class MissingS3ObjectDecryptor implements S3ObjectDecryptor {

    @Override
    public Path decryptToLocal(String bucketName, String prefix) throws DecryptException {
        throw new DecryptException(
                "No S3ObjectDecryptor implementation is configured. "
                        + "Provide a Spring bean that wraps the existing Java decrypt sample code.");
    }
}
