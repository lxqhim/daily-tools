package com.dailytools.s3migration.inventory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

@Component
public class InventoryCsvParser {

    public void parseGzip(InputStream inputStream, String fileSchema, InventoryRowHandler handler) throws Exception {
        parseGzip(inputStream, fileSchema, handler, failure -> {
            throw new IllegalArgumentException(
                    "Malformed inventory row " + failure.recordNumber() + ": " + failure.message(),
                    failure.cause());
        });
    }

    public void parseGzip(
            InputStream inputStream,
            String fileSchema,
            InventoryRowHandler handler,
            InventoryRowParseFailureHandler failureHandler)
            throws Exception {
        Map<String, Integer> indexes = schemaIndexes(fileSchema);
        int bucketIndex = requiredIndex(indexes, "bucket");
        int keyIndex = requiredIndex(indexes, "key");
        int lastModifiedIndex = requiredIndex(indexes, "lastmodifieddate");
        int sizeIndex = requiredIndex(indexes, "size");
        int eTagIndex = requiredIndex(indexes, "etag");

        try (GZIPInputStream gzipInputStream = new GZIPInputStream(inputStream);
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(gzipInputStream, StandardCharsets.UTF_8));
                CSVParser csvParser = CSVFormat.DEFAULT.parse(reader)) {
            for (CSVRecord record : csvParser) {
                String rawBucket = safeGet(record, bucketIndex);
                String rawKey = safeGet(record, keyIndex);
                String decodedKey = null;
                String rawLastModified = safeGet(record, lastModifiedIndex);
                String rawSize = safeGet(record, sizeIndex);
                String rawETag = safeGet(record, eTagIndex);
                InventoryObject object;
                try {
                    decodedKey = S3InventoryKeyDecoder.decode(requiredValue(rawKey, "key"));
                    object = new InventoryObject(
                            requiredValue(rawBucket, "bucket"),
                            decodedKey,
                            Instant.parse(requiredValue(rawLastModified, "lastModifiedDate")),
                            Long.parseLong(requiredValue(rawSize, "size")),
                            requiredValue(rawETag, "eTag"));
                } catch (RuntimeException exception) {
                    failureHandler.handle(new InventoryRowParseFailure(
                            record.getRecordNumber(),
                            rawBucket,
                            rawKey,
                            decodedKey,
                            rawLastModified,
                            rawSize,
                            rawETag,
                            exception.getMessage(),
                            exception));
                    continue;
                }
                handler.handle(object);
            }
        }
    }

    private static String safeGet(CSVRecord record, int index) {
        try {
            return record.get(index);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String requiredValue(String value, String column) {
        if (value == null) {
            throw new IllegalArgumentException("Missing inventory column value: " + column);
        }
        return value;
    }

    private static Map<String, Integer> schemaIndexes(String fileSchema) {
        String[] columns = fileSchema.split(",");
        Map<String, Integer> indexes = new HashMap<>();
        for (int index = 0; index < columns.length; index++) {
            indexes.put(normalize(columns[index]), index);
        }
        return indexes;
    }

    private static int requiredIndex(Map<String, Integer> indexes, String column) {
        Integer index = indexes.get(column);
        if (index == null) {
            throw new IllegalArgumentException("Inventory schema is missing column: " + column);
        }
        return index;
    }

    private static String normalize(String columnName) {
        return columnName.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
