package com.dailytools.s3migration.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dailytools.s3migration.config.MigrationProperties;
import com.dailytools.s3migration.decrypt.DecryptException;
import com.dailytools.s3migration.failed.FailedLog;
import com.dailytools.s3migration.inventory.InventoryCsvParser;
import com.dailytools.s3migration.inventory.S3InventoryManifestReader;
import com.dailytools.s3migration.s3.S3ObjectReader;
import com.dailytools.s3migration.s3.S3Uri;
import com.dailytools.s3migration.shard.HashShardAssigner;
import com.dailytools.s3migration.state.BaselineStatus;
import com.dailytools.s3migration.state.MigrationState;
import com.dailytools.s3migration.state.StateStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationServiceBaselineTest {

    private static final String MANIFEST_URI = "s3://inventory-bucket/reports/manifest.json";
    private static final String FILE_ONE = "reports/data/file-1.csv.gz";
    private static final String FILE_TWO = "reports/data/file-2.csv.gz";

    @TempDir
    Path tempDir;

    @Test
    void baselineCheckpointsCompletedFilesAndResumesAfterAbort() throws Exception {
        MigrationProperties properties = baselineProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        StateStore stateStore = new StateStore(objectMapper);
        FailedLog failedLog = new FailedLog(objectMapper);
        Map<String, byte[]> objects = inventoryObjects();
        List<String> uploadedFirstRun = new CopyOnWriteArrayList<>();

        MigrationService firstRun = service(
                properties,
                stateStore,
                failedLog,
                new FailingS3ObjectReader(objects, Set.of(FILE_TWO)),
                uploadedFirstRun);

        assertThatThrownBy(firstRun::run)
                .isInstanceOf(IOException.class)
                .hasMessageContaining(FILE_TWO);

        MigrationState abortedState = stateStore.loadOrCreate(properties.getPaths().getState(), properties);
        assertThat(abortedState.getBaselineStatus()).isEqualTo(BaselineStatus.ABORTED);
        assertThat(abortedState.getBaselineCompletedFileCount()).isEqualTo(1);
        assertThat(abortedState.getBaselineLastCompletedFile()).isEqualTo(FILE_ONE);
        assertThat(abortedState.getBaselineCompletedFiles()).isEmpty();
        assertThat(abortedState.getCounters().getSuccess()).isEqualTo(1);
        assertThat(abortedState.getBaselineObservedMaxLastModified())
                .isEqualTo(Instant.parse("2026-05-18T00:00:00Z"));
        assertThat(abortedState.getLastErrorCode()).isEqualTo("BASELINE_ABORTED");
        assertThat(uploadedFirstRun).containsExactly("first.txt");

        List<String> uploadedSecondRun = new CopyOnWriteArrayList<>();
        MigrationService secondRun = service(
                properties,
                stateStore,
                failedLog,
                new FailingS3ObjectReader(objects, Set.of()),
                uploadedSecondRun);

        secondRun.run();

        MigrationState completedState = stateStore.loadOrCreate(properties.getPaths().getState(), properties);
        assertThat(uploadedSecondRun).containsExactly("second file.txt");
        assertThat(completedState.getBaselineStatus()).isEqualTo(BaselineStatus.COMPLETED);
        assertThat(completedState.getBaselineCompletedFileCount()).isEqualTo(2);
        assertThat(completedState.getBaselineLastCompletedFile()).isEqualTo(FILE_TWO);
        assertThat(completedState.getBaselineCompletedFiles()).isEmpty();
        assertThat(completedState.getCounters().getSuccess()).isEqualTo(2);
        assertThat(completedState.getCounters().getFailed()).isZero();
        assertThat(completedState.getCurrentMode()).isEqualTo(JobMode.BASELINE);
        assertThat(completedState.getBaselineInventoryTimestamp())
                .isEqualTo(Instant.parse("2026-05-17T00:00:00Z"));
        assertThat(completedState.getBaselineObservedMaxLastModified())
                .isEqualTo(Instant.parse("2026-05-20T00:00:00Z"));
        assertThat(completedState.getDeltaWatermark()).isEqualTo(Instant.parse("2026-05-18T00:00:00Z"));
        assertThat(completedState.getDeltaCandidateWatermark())
                .isEqualTo(Instant.parse("2026-05-18T00:00:00Z"));
        assertThat(completedState.getLastErrorCode()).isNull();
    }

    @Test
    void uploadDryRunBaselineDoesNotWriteMigrationStateOrUpload() throws Exception {
        MigrationProperties properties = baselineProperties();
        properties.getUpload().setDryRun(true);
        ObjectMapper objectMapper = new ObjectMapper();
        StateStore stateStore = new StateStore(objectMapper);
        FailedLog failedLog = new FailedLog(objectMapper);
        List<String> uploadedKeys = new CopyOnWriteArrayList<>();

        MigrationService service = service(
                properties,
                stateStore,
                failedLog,
                new FailingS3ObjectReader(inventoryObjects(), Set.of()),
                uploadedKeys);

        service.run();

        assertThat(properties.getPaths().getState()).doesNotExist();
        assertThat(uploadedKeys).isEmpty();
        try (var files = Files.list(tempDir)) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".bin"))
                            .count())
                    .isEqualTo(2);
        }
    }

    @Test
    void uploadDryRunBaselineStopsAfterSampleSize() throws Exception {
        MigrationProperties properties = baselineProperties();
        properties.getUpload().setDryRun(true);
        properties.getUpload().setDryRunSampleSize(1);
        ObjectMapper objectMapper = new ObjectMapper();
        StateStore stateStore = new StateStore(objectMapper);
        FailedLog failedLog = new FailedLog(objectMapper);
        List<String> uploadedKeys = new CopyOnWriteArrayList<>();

        MigrationService service = service(
                properties,
                stateStore,
                failedLog,
                new FailingS3ObjectReader(inventoryObjects(), Set.of()),
                uploadedKeys);

        service.run();

        assertThat(properties.getPaths().getState()).doesNotExist();
        assertThat(uploadedKeys).isEmpty();
        try (var files = Files.list(tempDir)) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".bin"))
                            .count())
                    .isEqualTo(1);
        }
    }

    private MigrationService service(
            MigrationProperties properties,
            StateStore stateStore,
            FailedLog failedLog,
            S3ObjectReader objectReader,
            List<String> uploadedKeys) {
        ObjectProcessor objectProcessor = new ObjectProcessor(
                (bucketName, prefix) -> decryptedFile(prefix),
                (bucketName, key, localFile) -> uploadedKeys.add(key),
                failedLog,
                properties);
        return new MigrationService(
                properties,
                stateStore,
                new S3InventoryManifestReader(objectReader, new ObjectMapper()),
                objectReader,
                new InventoryCsvParser(),
                new HashShardAssigner(),
                objectProcessor,
                failedLog,
                new WatermarkPolicy(properties));
    }

    private Path decryptedFile(String prefix) throws DecryptException {
        try {
            Path path = tempDir.resolve(UUID.randomUUID() + ".bin");
            Files.writeString(path, prefix);
            return path;
        } catch (IOException exception) {
            throw new DecryptException("failed to write decrypted fixture", exception);
        }
    }

    private MigrationProperties baselineProperties() {
        MigrationProperties properties = new MigrationProperties();
        properties.getJob().setMode(JobMode.BASELINE);
        properties.getJob().setRunId("baseline-test");
        properties.getS3().setSourceBucket("source-bucket");
        properties.getS3().setTargetBucket("target-bucket");
        properties.getInventory().setManifestUri(MANIFEST_URI);
        properties.getShard().setTotal(1);
        properties.getShard().setIndex(0);
        properties.getPaths().setState(tempDir.resolve("state.json"));
        properties.getPaths().setFailedLog(tempDir.resolve("failed.log"));
        properties.getWorker().setConcurrency(1);
        properties.getWorker().setQueueSize(1);
        properties.getObservability().setProgressLogInterval(Duration.ofHours(1));
        return properties;
    }

    private static Map<String, byte[]> inventoryObjects() throws Exception {
        String manifest = """
                {
                  "creationTimestamp": "2026-05-17T00:00:00Z",
                  "fileSchema": "Bucket, Key, LastModifiedDate, Size, ETag",
                  "files": [
                    {"key": "%s", "size": 100, "MD5checksum": "md5-1"},
                    {"key": "%s", "size": 100, "MD5checksum": "md5-2"}
                  ]
                }
                """
                .formatted(FILE_ONE, FILE_TWO);
        return Map.of(
                MANIFEST_URI,
                manifest.getBytes(StandardCharsets.UTF_8),
                "s3://inventory-bucket/" + FILE_ONE,
                gzip("source-bucket,first.txt,2026-05-18T00:00:00Z,10,etag-1\n"),
                "s3://inventory-bucket/" + FILE_TWO,
                gzip("source-bucket,second%20file.txt,2026-05-20T00:00:00Z,20,etag-2\n"));
    }

    private static byte[] gzip(String value) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
            gzipOutputStream.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return outputStream.toByteArray();
    }

    private static class FailingS3ObjectReader implements S3ObjectReader {

        private final Map<String, byte[]> objects;
        private final Set<String> failingKeys;

        private FailingS3ObjectReader(Map<String, byte[]> objects, Set<String> failingKeys) {
            this.objects = objects;
            this.failingKeys = failingKeys;
        }

        @Override
        public InputStream open(S3Uri uri) throws IOException {
            if (failingKeys.contains(uri.key())) {
                throw new IOException("boom reading " + uri.key());
            }
            byte[] value = objects.get(uri.toUriString());
            if (value == null) {
                throw new IOException("missing fixture " + uri.toUriString());
            }
            return new ByteArrayInputStream(value);
        }
    }
}
