package com.dailytools.s3migration.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class S3UriTest {

    @Test
    void parsesBucketAndKey() {
        S3Uri uri = S3Uri.parse("s3://inventory-bucket/path/to/manifest.json");

        assertThat(uri.bucket()).isEqualTo("inventory-bucket");
        assertThat(uri.key()).isEqualTo("path/to/manifest.json");
        assertThat(uri.toUriString()).isEqualTo("s3://inventory-bucket/path/to/manifest.json");
    }

    @Test
    void rejectsNonS3Uri() {
        assertThatThrownBy(() -> S3Uri.parse("https://example.com/a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("s3://");
    }

    @Test
    void rejectsUriWithoutKey() {
        assertThatThrownBy(() -> S3Uri.parse("s3://bucket"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
    }
}
