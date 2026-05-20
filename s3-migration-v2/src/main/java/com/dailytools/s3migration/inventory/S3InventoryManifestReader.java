package com.dailytools.s3migration.inventory;

import com.dailytools.s3migration.s3.S3ObjectReader;
import com.dailytools.s3migration.s3.S3Uri;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class S3InventoryManifestReader {

    private final S3ObjectReader objectReader;
    private final ObjectMapper objectMapper;

    public S3InventoryManifestReader(S3ObjectReader objectReader, ObjectMapper objectMapper) {
        this.objectReader = objectReader;
        this.objectMapper = objectMapper;
    }

    public InventoryManifest read(S3Uri manifestUri) throws IOException {
        JsonNode root = objectMapper.readTree(objectReader.readUtf8(manifestUri));
        String fileSchema = requiredText(root, "fileSchema");
        Instant creationTimestamp = parseCreationTimestamp(root.path("creationTimestamp"));
        List<InventoryDataFile> files = new ArrayList<>();
        JsonNode filesNode = root.path("files");
        if (!filesNode.isArray()) {
            throw new IOException("Inventory manifest files field must be an array");
        }
        for (JsonNode fileNode : filesNode) {
            files.add(new InventoryDataFile(
                    requiredText(fileNode, "key"),
                    fileNode.path("size").asLong(-1),
                    optionalText(fileNode, "MD5checksum")));
        }
        return new InventoryManifest(fileSchema, creationTimestamp, List.copyOf(files));
    }

    private static Instant parseCreationTimestamp(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return Instant.ofEpochMilli(node.asLong());
        }
        String text = node.asText();
        if (text.matches("\\d+")) {
            return Instant.ofEpochMilli(Long.parseLong(text));
        }
        return Instant.parse(text);
    }

    private static String requiredText(JsonNode node, String fieldName) throws IOException {
        String value = optionalText(node, fieldName);
        if (value == null || value.isBlank()) {
            throw new IOException("Inventory manifest missing required field: " + fieldName);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
