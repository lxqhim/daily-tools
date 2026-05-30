package com.dailytools.s3migration.s3;

import java.net.URI;

public record S3Uri(String bucket, String key) {

    public static S3Uri parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("S3 URI must not be blank");
        }
        URI uri = URI.create(value);
        if (!"s3".equals(uri.getScheme())) {
            throw new IllegalArgumentException("S3 URI must start with s3://");
        }
        String bucket = uri.getHost();
        String rawPath = uri.getRawPath();
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("S3 URI bucket is required");
        }
        if (rawPath == null || rawPath.length() <= 1) {
            throw new IllegalArgumentException("S3 URI key is required");
        }
        return new S3Uri(bucket, rawPath.substring(1));
    }

    public String toUriString() {
        return "s3://" + bucket + "/" + key;
    }
}
