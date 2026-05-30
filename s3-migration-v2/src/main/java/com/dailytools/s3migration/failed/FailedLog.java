package com.dailytools.s3migration.failed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class FailedLog {

    private final ObjectMapper objectMapper;

    public FailedLog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public synchronized void append(Path path, FailedRecord record) {
        append(path, record, Long.MAX_VALUE);
    }

    public synchronized void append(Path path, FailedRecord record, long maxBytes) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            failIfLimitAlreadyExceeded(path, maxBytes);
            String line = objectMapper.writeValueAsString(record);
            long currentSize = Files.exists(path) ? Files.size(path) : 0L;
            long projectedSize = currentSize + line.getBytes(StandardCharsets.UTF_8).length + System.lineSeparator().length();
            if (projectedSize > maxBytes) {
                throw new FailedLogLimitExceededException(path, maxBytes, projectedSize);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(
                    path,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to append failed log: " + path, exception);
        }
    }

    private static void failIfLimitAlreadyExceeded(Path path, long maxBytes) throws IOException {
        if (Files.exists(path) && Files.size(path) > maxBytes) {
            throw new FailedLogLimitExceededException(path, maxBytes, Files.size(path));
        }
    }

    public List<FailedRecord> readAll(List<Path> paths) throws IOException {
        List<FailedRecord> records = new ArrayList<>();
        readEach(paths, records::add);
        return records;
    }

    public void readEach(List<Path> paths, Consumer<FailedRecord> handler) throws IOException {
        for (Path path : paths) {
            if (!Files.exists(path)) {
                continue;
            }
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        handler.accept(objectMapper.readValue(line, FailedRecord.class));
                    }
                }
            }
        }
    }
}
