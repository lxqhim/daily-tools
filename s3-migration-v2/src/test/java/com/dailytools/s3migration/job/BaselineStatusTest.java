package com.dailytools.s3migration.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.dailytools.s3migration.state.BaselineStatus;
import org.junit.jupiter.api.Test;

class BaselineStatusTest {

    @Test
    void allowsDeltaOnlyAfterCompletedStates() {
        assertThat(BaselineStatus.COMPLETED.allowsDelta()).isTrue();
        assertThat(BaselineStatus.COMPLETED_WITH_FAILURES.allowsDelta()).isTrue();
        assertThat(BaselineStatus.NOT_STARTED.allowsDelta()).isFalse();
        assertThat(BaselineStatus.RUNNING.allowsDelta()).isFalse();
        assertThat(BaselineStatus.ABORTED.allowsDelta()).isFalse();
    }
}
