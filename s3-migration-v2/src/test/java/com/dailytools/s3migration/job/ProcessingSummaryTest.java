package com.dailytools.s3migration.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProcessingSummaryTest {

    @Test
    void keepsDryRunSuccessSeparateFromUploadedSuccess() {
        ProcessingSummary summary = new ProcessingSummary();

        summary.add(ObjectProcessResult.SUCCESS);
        summary.add(ObjectProcessResult.DRY_RUN_SUCCESS);

        assertThat(summary.counters().success()).isEqualTo(1);
        assertThat(summary.counters().dryRunSuccess()).isEqualTo(1);
        assertThat(summary.counters().failed()).isZero();
    }
}
