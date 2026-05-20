package com.dailytools.s3migration.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.dailytools.s3migration.config.MigrationProperties;
import com.dailytools.s3migration.decrypt.DecryptException;
import com.dailytools.s3migration.failed.FailedLog;
import com.dailytools.s3migration.failed.FailedRecord;
import com.dailytools.s3migration.inventory.InventoryObject;
import com.dailytools.s3migration.shard.HashShardAssigner;
import com.dailytools.s3migration.state.MigrationState;
import com.dailytools.s3migration.state.StateStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationServiceRetryTest {

    @TempDir
    Path tempDir;

    @Test
    void retryUpdatesStateCounters() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MigrationProperties properties = properties();
        FailedLog failedLog = new FailedLog(objectMapper);
        failedLog.append(properties.getPaths().getRetryInputs().getFirst(), failedRecord("a.txt"));
        failedLog.append(properties.getPaths().getRetryInputs().getFirst(), failedRecord("b.txt"));
        ObjectProcessor objectProcessor = new ObjectProcessor(
                (bucket, prefix) -> {
                    Path path = tempDir.resolve(UUID.randomUUID() + ".txt");
                    writeString(path, prefix);
                    return path;
                },
                (bucketName, key, localFile) -> {},
                failedLog,
                properties);
        StateStore stateStore = new StateStore(objectMapper);
        MigrationService service = new MigrationService(
                properties,
                stateStore,
                null,
                null,
                null,
                new HashShardAssigner(),
                objectProcessor,
                failedLog,
                new WatermarkPolicy(properties));

        service.run();

        MigrationState state = stateStore.loadOrCreate(properties.getPaths().getState(), properties);
        assertThat(state.getCounters().getRetried()).isEqualTo(2);
        assertThat(state.getCounters().getSuccess()).isEqualTo(2);
        assertThat(state.getCounters().getFailed()).isZero();
        assertThat(state.getCurrentMode()).isEqualTo(JobMode.RETRY);
    }

    private FailedRecord failedRecord(String key) {
        InventoryObject object = new InventoryObject("source", key, Instant.parse("2026-05-20T00:00:00Z"), 1L, "etag");
        return FailedRecord.of("previous-run", JobMode.BASELINE, 1, 0, "source", "target", object, "ERR", "failed");
    }

    private static void writeString(Path path, String value) throws DecryptException {
        try {
            Files.writeString(path, value);
        } catch (Exception exception) {
            throw new DecryptException("failed to write test file", exception);
        }
    }

    private MigrationProperties properties() {
        MigrationProperties properties = new MigrationProperties();
        properties.getJob().setMode(JobMode.RETRY);
        properties.getS3().setSourceBucket("source");
        properties.getS3().setTargetBucket("target");
        properties.getShard().setTotal(1);
        properties.getShard().setIndex(0);
        properties.getPaths().setState(tempDir.resolve("state.json"));
        properties.getPaths().setRetryFailedLog(tempDir.resolve("retry-failed.log"));
        properties.getPaths().setRetryInputs(List.of(tempDir.resolve("failed.log")));
        return properties;
    }
}
