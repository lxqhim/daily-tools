package com.dailytools.s3migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class S3MigrationApplicationTest {

    @Test
    void contextLoads() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(S3MigrationApplication.class)
                .properties(
                        "migration.job.mode=RETRY",
                        "migration.s3.source-bucket=source",
                        "migration.s3.target-bucket=target",
                        "migration.paths.retry-inputs=/tmp/does-not-exist.log")
                .run()) {
            assertThat(context.isRunning()).isTrue();
        }
    }
}
