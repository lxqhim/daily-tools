package com.dailytools.s3migration.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.dailytools.s3migration.s3.S3ObjectReader;
import com.dailytools.s3migration.s3.S3Uri;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class S3InventoryManifestReaderTest {

    @Test
    void readsManifestFilesFromJson() throws Exception {
        String manifestJson = """
                {
                  "creationTimestamp": "1779235200000",
                  "fileSchema": "Bucket, Key, LastModifiedDate, Size, ETag",
                  "files": [
                    {"key": "inventory/data/1.csv.gz", "size": 100, "MD5checksum": "abc"},
                    {"key": "inventory/data/2.csv.gz", "size": 200, "MD5checksum": "def"}
                  ]
                }
                """;
        S3InventoryManifestReader reader =
                new S3InventoryManifestReader(new StaticS3ObjectReader(manifestJson), new ObjectMapper());

        InventoryManifest manifest = reader.read(S3Uri.parse("s3://inventory-bucket/path/manifest.json"));

        assertThat(manifest.fileSchema()).isEqualTo("Bucket, Key, LastModifiedDate, Size, ETag");
        assertThat(manifest.creationTimestamp()).isEqualTo(Instant.ofEpochMilli(1_779_235_200_000L));
        assertThat(manifest.files())
                .containsExactly(
                        new InventoryDataFile("inventory/data/1.csv.gz", 100L, "abc"),
                        new InventoryDataFile("inventory/data/2.csv.gz", 200L, "def"));
    }

    private record StaticS3ObjectReader(String value) implements S3ObjectReader {
        @Override
        public InputStream open(S3Uri uri) throws IOException {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
