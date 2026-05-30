package com.dailytools.s3migration.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BlockingRejectedExecutionHandlerTest {

    @Test
    void queuesRejectedTaskWithoutRunningItOnCallerThread() {
        MigrationService.BlockingRejectedExecutionHandler handler =
                new MigrationService.BlockingRejectedExecutionHandler();
        ThreadPoolExecutor executor = executor();
        AtomicBoolean ran = new AtomicBoolean(false);
        Runnable task = () -> ran.set(true);

        handler.rejectedExecution(task, executor);

        assertThat(ran).isFalse();
        assertThat(executor.getQueue()).containsExactly(task);
        executor.shutdownNow();
    }

    @Test
    void rejectsWhenExecutorIsShutdown() {
        MigrationService.BlockingRejectedExecutionHandler handler =
                new MigrationService.BlockingRejectedExecutionHandler();
        ThreadPoolExecutor executor = executor();
        executor.shutdownNow();

        assertThatThrownBy(() -> handler.rejectedExecution(() -> {}, executor))
                .isInstanceOf(RejectedExecutionException.class)
                .hasMessageContaining("Executor is shut down");
    }

    private static ThreadPoolExecutor executor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
