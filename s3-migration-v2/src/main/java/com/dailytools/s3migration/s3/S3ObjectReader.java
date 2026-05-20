package com.dailytools.s3migration.s3;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public interface S3ObjectReader {

    InputStream open(S3Uri uri) throws IOException;

    default String readUtf8(S3Uri uri) throws IOException {
        try (InputStream inputStream = open(uri)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
