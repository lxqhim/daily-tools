package com.dailytools.s3migration.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DataFileProgressTest {

    @Test
    void logsOnlyWhenIntervalElapses() {
        Instant start = Instant.parse("2026-05-20T00:00:00Z");
        DataFileProgress progress = new DataFileProgress(start, Duration.ofSeconds(5));

        assertThat(progress.shouldLog(start.plusSeconds(4))).isFalse();
        assertThat(progress.pollMillisUntilNextLog(start.plusSeconds(4))).isEqualTo(1000L);
        assertThat(progress.shouldLog(start.plusSeconds(5))).isTrue();
        assertThat(progress.shouldLog(start.plusSeconds(6))).isFalse();
        assertThat(progress.shouldLog(start.plusSeconds(10))).isTrue();
    }

    @Test
    void zeroIntervalDisablesProgressLogs() {
        Instant start = Instant.parse("2026-05-20T00:00:00Z");
        DataFileProgress progress = new DataFileProgress(start, Duration.ZERO);

        assertThat(progress.isLoggingEnabled()).isFalse();
        assertThat(progress.shouldLog(start.plusSeconds(60))).isFalse();
    }
}
