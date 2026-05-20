package com.dailytools.s3migration.failed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dailytools.s3migration.inventory.InventoryObject;
import com.dailytools.s3migration.job.JobMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FailedLogTest {

    @TempDir
    Path tempDir;

    @Test
    void writesAndReadsJsonLines() throws Exception {
        FailedLog failedLog = new FailedLog(new ObjectMapper());
        Path path = tempDir.resolve("failed.log");
        InventoryObject object =
                new InventoryObject("source", "folder/a+b,中文.txt", Instant.parse("2026-05-20T00:00:00Z"), 1L, "e");

        failedLog.append(path, FailedRecord.of(
                "run-1", JobMode.BASELINE, 8, 1, "source", "target", object, "ERR", "failed"));

        List<FailedRecord> records = failedLog.readAll(List.of(path));

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().key()).isEqualTo("folder/a+b,中文.txt");
        assertThat(records.getFirst().errorCode()).isEqualTo("ERR");
    }

    @Test
    void failsFastWhenAppendWouldExceedConfiguredLimit() {
        FailedLog failedLog = new FailedLog(new ObjectMapper());
        Path path = tempDir.resolve("failed.log");
        InventoryObject object = new InventoryObject("source", "folder/object.txt", Instant.parse("2026-05-20T00:00:00Z"), 1L, "e");
        FailedRecord record =
                FailedRecord.of("run-1", JobMode.BASELINE, 8, 1, "source", "target", object, "ERR", "failed");

        assertThatThrownBy(() -> failedLog.append(path, record, 10L))
                .isInstanceOf(FailedLogLimitExceededException.class)
                .hasMessageContaining("maxBytes=10");
    }
}
