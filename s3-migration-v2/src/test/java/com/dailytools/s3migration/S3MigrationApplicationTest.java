package com.dailytools.s3migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dailytools.s3migration.decrypt.S3ObjectDecryptor;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ConfigurableApplicationContext;

class S3MigrationApplicationTest {

    private static final String[] DISABLE_MIGRATION_RUNNER = {"--migration.enabled=false"};

    @TempDir
    Path tempDir;

    @Test
    void contextLoadsWithProvidedDecryptor() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                        S3MigrationApplication.class, TestDecryptorConfiguration.class)
                .properties(baseProperties())
                .run(DISABLE_MIGRATION_RUNNER)) {
            assertThat(context.isRunning()).isTrue();
        }
    }

    @Test
    void contextLoadsWithLegacyKmsDecryptor() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(S3MigrationApplication.class)
                .run(args(
                        "--migration.enabled=false",
                        "--migration.job.mode=retry",
                        "--migration.s3.source-bucket=source",
                        "--migration.s3.target-bucket=target",
                        "--migration.paths.retry-inputs=/tmp/does-not-exist.log",
                        "--migration.decrypt.legacy-kms.enabled=true",
                        "--migration.decrypt.legacy-kms.kms-key-id=alias/test-key",
                        "--migration.paths.temp-dir=" + tempDir.resolve("decrypt-tmp")))) {
            assertThat(context.isRunning()).isTrue();
        }
    }

    @Test
    void contextFailsWithoutDecryptorWhenLegacyKmsIsDisabled() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(S3MigrationApplication.class)
                        .properties(baseProperties())
                        .run(DISABLE_MIGRATION_RUNNER))
                .hasMessageContaining("No S3ObjectDecryptor configured")
                .hasMessageContaining("migration.decrypt.legacy-kms.enabled=true");
    }

    private static String[] baseProperties() {
        return new String[] {
            "migration.job.mode=RETRY",
            "migration.s3.source-bucket=source",
            "migration.s3.target-bucket=target",
            "migration.paths.retry-inputs=/tmp/does-not-exist.log"
        };
    }

    private static String[] args(String... args) {
        return args;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestDecryptorConfiguration {

        @Bean
        S3ObjectDecryptor testDecryptor() {
            return (bucketName, prefix) -> {
                throw new UnsupportedOperationException("test decryptor should not be called");
            };
        }
    }
}
