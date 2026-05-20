package com.dailytools.s3migration.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.dailytools.s3migration.config.MigrationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void createsInitialStateWhenMissing() throws Exception {
        StateStore store = new StateStore(new ObjectMapper());
        Path statePath = tempDir.resolve("state.json");

        MigrationState state = store.loadOrCreate(statePath, properties());

        assertThat(state.getBaselineStatus()).isEqualTo(BaselineStatus.NOT_STARTED);
        assertThat(state.getShardTotal()).isEqualTo(8);
        assertThat(state.getShardIndex()).isEqualTo(3);
        assertThat(statePath).exists();
    }

    @Test
    void persistsCompletedFilesForResume() throws Exception {
        StateStore store = new StateStore(new ObjectMapper());
        Path statePath = tempDir.resolve("state.json");
        MigrationState state = store.loadOrCreate(statePath, properties());
        state.getBaselineCompletedFiles().add("data/1.csv.gz");

        store.write(statePath, state);
        MigrationState reloaded = store.loadOrCreate(statePath, properties());

        assertThat(reloaded.getBaselineCompletedFiles()).containsExactly("data/1.csv.gz");
    }

    @Test
    void persistsObservedWatermarkProgressForResume() throws Exception {
        StateStore store = new StateStore(new ObjectMapper());
        Path statePath = tempDir.resolve("state.json");
        MigrationState state = store.loadOrCreate(statePath, properties());
        state.setBaselineObservedMaxLastModified(Instant.parse("2026-04-12T00:00:00Z"));
        state.setDeltaObservedMaxLastModified(Instant.parse("2026-04-13T00:00:00Z"));
        state.setDeltaCandidateWatermark(Instant.parse("2026-04-13T00:00:00Z"));

        store.write(statePath, state);
        MigrationState reloaded = store.loadOrCreate(statePath, properties());

        assertThat(reloaded.getBaselineObservedMaxLastModified()).isEqualTo(Instant.parse("2026-04-12T00:00:00Z"));
        assertThat(reloaded.getDeltaObservedMaxLastModified()).isEqualTo(Instant.parse("2026-04-13T00:00:00Z"));
        assertThat(reloaded.getDeltaCandidateWatermark()).isEqualTo(Instant.parse("2026-04-13T00:00:00Z"));
    }

    @Test
    void persistsLastRunErrorForOperators() throws Exception {
        StateStore store = new StateStore(new ObjectMapper());
        Path statePath = tempDir.resolve("state.json");
        MigrationState state = store.loadOrCreate(statePath, properties());
        state.setLastErrorCode("DELTA_ABORTED");
        state.setLastErrorMessage("manifest failed");
        state.setLastErrorAt(Instant.parse("2026-04-13T00:00:00Z"));

        store.write(statePath, state);
        MigrationState reloaded = store.loadOrCreate(statePath, properties());

        assertThat(reloaded.getLastErrorCode()).isEqualTo("DELTA_ABORTED");
        assertThat(reloaded.getLastErrorMessage()).isEqualTo("manifest failed");
        assertThat(reloaded.getLastErrorAt()).isEqualTo(Instant.parse("2026-04-13T00:00:00Z"));
    }

    private static MigrationProperties properties() {
        MigrationProperties properties = new MigrationProperties();
        properties.getS3().setSourceBucket("source");
        properties.getS3().setTargetBucket("target");
        properties.getShard().setTotal(8);
        properties.getShard().setIndex(3);
        return properties;
    }
}
