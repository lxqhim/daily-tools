package com.dailytools.s3migration.s3;

import java.io.IOException;
import java.io.InputStream;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

public class AwsS3ObjectReader implements S3ObjectReader {

    private final S3Client s3Client;

    public AwsS3ObjectReader(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    @Override
    public InputStream open(S3Uri uri) throws IOException {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(uri.bucket())
                    .key(uri.key())
                    .build();
            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request);
            return response;
        } catch (RuntimeException exception) {
            throw new IOException("Failed to open " + uri.toUriString(), exception);
        }
    }
}
