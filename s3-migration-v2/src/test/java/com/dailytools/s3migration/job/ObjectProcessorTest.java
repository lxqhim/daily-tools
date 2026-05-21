package com.dailytools.s3migration.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.dailytools.s3migration.config.MigrationProperties;
import com.dailytools.s3migration.decrypt.DecryptException;
import com.dailytools.s3migration.failed.FailedLog;
import com.dailytools.s3migration.inventory.InventoryObject;
import com.dailytools.s3migration.upload.TargetUploader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObjectProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void uploadsReturnedLocalFileAndDeletesIt() throws Exception {
        Path decrypted = tempDir.resolve("decrypted.txt");
        MigrationProperties properties = properties();
        RecordingUploader uploader = new RecordingUploader();
        ObjectProcessor processor = new ObjectProcessor(
                (bucket, prefix) -> {
                    writeDecrypted(decrypted);
                    return decrypted;
                },
                uploader,
                new FailedLog(new ObjectMapper()),
                properties);

        ObjectProcessResult result =
                processor.process(object(), JobMode.BASELINE, "run-1", tempDir.resolve("failed.log"));

        assertThat(result).isEqualTo(ObjectProcessResult.SUCCESS);
        assertThat(uploader.uploadedKey).isEqualTo("folder/object.txt");
        assertThat(decrypted).doesNotExist();
    }

    @Test
    void writesFailedLogWhenDecryptFails() throws Exception {
        MigrationProperties properties = properties();
        ObjectProcessor processor = new ObjectProcessor(
                (bucket, prefix) -> {
                    throw new DecryptException("boom");
                },
                new RecordingUploader(),
                new FailedLog(new ObjectMapper()),
                properties);
        Path failedLog = tempDir.resolve("failed.log");

        ObjectProcessResult result = processor.process(object(), JobMode.BASELINE, "run-1", failedLog);

        assertThat(result).isEqualTo(ObjectProcessResult.FAILED);
        assertThat(Files.readString(failedLog)).contains("OBJECT_PROCESSING_FAILED").contains("boom");
    }

    @Test
    void deletesLocalFileWhenUploadFails() throws Exception {
        Path decrypted = tempDir.resolve("decrypted.txt");
        MigrationProperties properties = properties();
        TargetUploader failingUploader = (bucketName, key, localFile) -> {
            throw new IOException("upload failed");
        };
        ObjectProcessor processor = new ObjectProcessor(
                (bucket, prefix) -> {
                    writeDecrypted(decrypted);
                    return decrypted;
                },
                failingUploader,
                new FailedLog(new ObjectMapper()),
                properties);

        ObjectProcessResult result =
                processor.process(object(), JobMode.BASELINE, "run-1", tempDir.resolve("failed.log"));

        assertThat(result).isEqualTo(ObjectProcessResult.FAILED);
        assertThat(decrypted).doesNotExist();
    }

    @Test
    void dryRunSkipsUploadAndKeepsLocalFile() throws Exception {
        Path decrypted = tempDir.resolve("decrypted.txt");
        MigrationProperties properties = properties();
        properties.getUpload().setDryRun(true);
        RecordingUploader uploader = new RecordingUploader();
        ObjectProcessor processor = new ObjectProcessor(
                (bucket, prefix) -> {
                    writeDecrypted(decrypted);
                    return decrypted;
                },
                uploader,
                new FailedLog(new ObjectMapper()),
                properties);

        ObjectProcessResult result =
                processor.process(object(), JobMode.BASELINE, "run-1", tempDir.resolve("failed.log"));

        assertThat(result).isEqualTo(ObjectProcessResult.DRY_RUN_SUCCESS);
        assertThat(uploader.uploadedKey).isNull();
        assertThat(decrypted).exists();
        assertThat(Files.readString(decrypted)).isEqualTo("content");
    }

    private static InventoryObject object() {
        return new InventoryObject("source", "folder/object.txt", Instant.parse("2026-05-20T00:00:00Z"), 1L, "etag");
    }

    private static MigrationProperties properties() {
        MigrationProperties properties = new MigrationProperties();
        properties.getS3().setSourceBucket("source");
        properties.getS3().setTargetBucket("target");
        properties.getShard().setTotal(8);
        properties.getShard().setIndex(1);
        return properties;
    }

    private static void writeDecrypted(Path path) throws DecryptException {
        try {
            Files.writeString(path, "content");
        } catch (IOException exception) {
            throw new DecryptException("failed to write test file", exception);
        }
    }

    private static class RecordingUploader implements TargetUploader {
        private String uploadedKey;

        @Override
        public void upload(String bucketName, String key, Path localFile) {
            uploadedKey = key;
        }
    }
}
