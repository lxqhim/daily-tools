package com.dailytools.s3migration.decrypt;

import java.nio.file.Path;

public interface S3ObjectDecryptor {

    /**
     * Decrypts exactly one source object into a local file.
     *
     * @param bucketName source bucket name
     * @param prefix decoded S3 object key, not a bulk prefix
     * @return path to the decrypted local file
     */
    Path decryptToLocal(String bucketName, String prefix) throws DecryptException;
}
