package com.dailytools.s3migration.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class InventoryCsvParserTest {

    private final InventoryCsvParser parser = new InventoryCsvParser();

    @Test
    void parsesGzipCsvWithUrlDecodedKey() throws Exception {
        String csv = "source-bucket,folder/a%20b.txt,2026-05-20T00:00:00Z,42,etag-1\n";
        List<InventoryObject> objects = new ArrayList<>();

        parser.parseGzip(
                new ByteArrayInputStream(gzip(csv)),
                "Bucket, Key, LastModifiedDate, Size, ETag",
                objects::add);

        assertThat(objects).containsExactly(new InventoryObject(
                "source-bucket", "folder/a b.txt", Instant.parse("2026-05-20T00:00:00Z"), 42L, "etag-1"));
    }

    @Test
    void handlesCommaInsideQuotedKey() throws Exception {
        String csv = "source-bucket,\"folder/a%2Cb.txt\",2026-05-20T00:00:00Z,42,etag-1\n";
        List<InventoryObject> objects = new ArrayList<>();

        parser.parseGzip(
                new ByteArrayInputStream(gzip(csv)),
                "Bucket, Key, LastModifiedDate, Size, ETag",
                objects::add);

        assertThat(objects.getFirst().key()).isEqualTo("folder/a,b.txt");
    }

    @Test
    void reportsMalformedRowsAndContinuesParsing() throws Exception {
        String csv = "source-bucket,folder/bad.txt,not-a-date,42,etag-bad\n"
                + "source-bucket,folder/good.txt,2026-05-20T00:00:00Z,42,etag-good\n";
        List<InventoryObject> objects = new ArrayList<>();
        List<InventoryRowParseFailure> failures = new ArrayList<>();

        parser.parseGzip(
                new ByteArrayInputStream(gzip(csv)),
                "Bucket, Key, LastModifiedDate, Size, ETag",
                objects::add,
                failures::add);

        assertThat(objects).containsExactly(new InventoryObject(
                "source-bucket", "folder/good.txt", Instant.parse("2026-05-20T00:00:00Z"), 42L, "etag-good"));
        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst().recordNumber()).isEqualTo(1L);
        assertThat(failures.getFirst().decodedKey()).isEqualTo("folder/bad.txt");
        assertThat(failures.getFirst().message()).contains("not-a-date");
    }

    private static byte[] gzip(String value) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
            gzipOutputStream.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return outputStream.toByteArray();
    }
}
