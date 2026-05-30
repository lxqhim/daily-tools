package com.dailytools.s3migration.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.dailytools.s3migration.config.MigrationProperties;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WatermarkPolicyTest {

    @Test
    void configuredInitialWatermarkWinsAfterBaseline() {
        Instant configured = Instant.parse("2026-04-01T00:00:00Z");
        ProcessingSummary summary = new ProcessingSummary();
        summary.observeLastModified(Instant.parse("2026-04-20T00:00:00Z"));

        assertThat(policy(configured).initialAfterBaseline(summary)).isEqualTo(configured);
    }

    @Test
    void baselineWatermarkUsesObservedMaxLastModifiedWhenNoConfiguredWatermarkExists() {
        ProcessingSummary summary = new ProcessingSummary();
        summary.observeLastModified(Instant.parse("2026-04-10T00:00:00Z"));
        summary.observeLastModified(Instant.parse("2026-04-12T00:00:00Z"));

        assertThat(policy(null, Duration.ZERO).initialAfterBaseline(summary))
                .isEqualTo(Instant.parse("2026-04-12T00:00:00Z"));
    }

    @Test
    void emptyBaselineFallsBackToEpoch() {
        assertThat(policy(null).initialAfterBaseline(new ProcessingSummary())).isEqualTo(Instant.EPOCH);
    }

    @Test
    void missingDeltaStateUsesConfiguredWatermarkOrEpoch() {
        Instant configured = Instant.parse("2026-04-01T00:00:00Z");

        assertThat(policy(configured).initialForDeltaWhenStateMissing()).isEqualTo(configured);
        assertThat(policy(null).initialForDeltaWhenStateMissing()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void deltaWatermarkAdvancesOnlyToObservedMaxLastModifiedMinusLookback() {
        Instant previous = Instant.parse("2026-04-10T00:00:00Z");
        ProcessingSummary summary = new ProcessingSummary();
        summary.observeLastModified(Instant.parse("2026-04-12T00:00:00Z"));

        assertThat(policy(null, Duration.ofHours(48)).advanceAfterDelta(previous, summary))
                .isEqualTo(Instant.parse("2026-04-10T00:00:00Z"));
        assertThat(policy(null, Duration.ofHours(24)).advanceAfterDelta(previous, summary))
                .isEqualTo(Instant.parse("2026-04-11T00:00:00Z"));
    }

    @Test
    void baselineWatermarkAppliesLookbackWhenNoConfiguredWatermarkExists() {
        ProcessingSummary summary = new ProcessingSummary();
        summary.observeLastModified(Instant.parse("2026-04-12T00:00:00Z"));

        assertThat(policy(null, Duration.ofHours(48)).initialAfterBaseline(summary))
                .isEqualTo(Instant.parse("2026-04-10T00:00:00Z"));
    }

    @Test
    void deltaWatermarkDoesNotMoveBackwardOrAdvanceOnEmptyRuns() {
        Instant previous = Instant.parse("2026-04-10T00:00:00Z");
        ProcessingSummary older = new ProcessingSummary();
        older.observeLastModified(Instant.parse("2026-04-09T00:00:00Z"));

        assertThat(policy(null).advanceAfterDelta(previous, older)).isEqualTo(previous);
        assertThat(policy(null).advanceAfterDelta(previous, new ProcessingSummary())).isEqualTo(previous);
    }

    private static WatermarkPolicy policy(Instant configuredWatermark) {
        return policy(configuredWatermark, Duration.ofHours(48));
    }

    private static WatermarkPolicy policy(Instant configuredWatermark, Duration deltaLookback) {
        MigrationProperties properties = new MigrationProperties();
        properties.getJob().setInitialWatermark(configuredWatermark);
        properties.getJob().setDeltaLookback(deltaLookback);
        return new WatermarkPolicy(properties);
    }
}
