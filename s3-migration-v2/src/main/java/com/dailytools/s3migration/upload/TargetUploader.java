package com.dailytools.s3migration.upload;

import java.io.IOException;
import java.nio.file.Path;

public interface TargetUploader {

    void upload(String bucketName, String key, Path localFile) throws IOException;
}
