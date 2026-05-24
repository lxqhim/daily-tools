package com.dailytools.s3migration.state;

import com.dailytools.s3migration.config.MigrationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class StateStore {

    private final ObjectMapper objectMapper;

    public StateStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public MigrationState loadOrCreate(Path path, MigrationProperties properties) throws IOException {
        if (Files.exists(path)) {
            return load(path, properties);
        }
        MigrationState state = new MigrationState();
        state.setShardTotal(properties.getShard().getTotal());
        state.setShardIndex(properties.getShard().getIndex());
        state.setSourceBucket(properties.getS3().getSourceBucket());
        state.setTargetBucket(properties.getS3().getTargetBucket());
        state.setBaselineStatus(BaselineStatus.NOT_STARTED);
        state.setJobStartedAt(Instant.now());
        write(path, state);
        return state;
    }

    public MigrationState load(Path path, MigrationProperties properties) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("State file does not exist: " + path);
        }
        MigrationState state = objectMapper.readValue(path.toFile(), MigrationState.class);
        state.migrateCompletedFileSetsToCursors();
        validateState(state, properties);
        return state;
    }

    public void write(Path path, MigrationState state) throws IOException {
        Path absolutePath = path.toAbsolutePath();
        Path parent = absolutePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tempFile = Files.createTempFile(parent, absolutePath.getFileName().toString(), ".tmp");
        try {
            objectMapper.writeValue(tempFile.toFile(), state);
            moveIntoPlace(tempFile, absolutePath);
        } catch (IOException | RuntimeException exception) {
            cleanupTempFile(tempFile, exception);
            throw exception;
        }
    }

    private static void moveIntoPlace(Path tempFile, Path absolutePath) throws IOException {
        try {
            Files.move(tempFile, absolutePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicMoveException) {
            try {
                Files.move(tempFile, absolutePath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackMoveException) {
                fallbackMoveException.addSuppressed(atomicMoveException);
                throw fallbackMoveException;
            }
        }
    }

    private static void cleanupTempFile(Path tempFile, Exception writeFailure) {
        try {
            Files.deleteIfExists(tempFile);
        } catch (Exception cleanupException) {
            writeFailure.addSuppressed(cleanupException);
        }
    }

    private static void validateState(MigrationState state, MigrationProperties properties) {
        if (state.getShardTotal() != properties.getShard().getTotal()
                || state.getShardIndex() != properties.getShard().getIndex()) {
            throw new IllegalStateException("Existing state shard does not match current shard configuration");
        }
        if (!properties.getS3().getSourceBucket().equals(state.getSourceBucket())) {
            throw new IllegalStateException("Existing state source bucket does not match current configuration");
        }
        if (!properties.getS3().getTargetBucket().equals(state.getTargetBucket())) {
            throw new IllegalStateException("Existing state target bucket does not match current configuration");
        }
    }
}
