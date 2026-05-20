package com.dailytools.s3migration.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dailytools.s3migration.job.JobMode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class MigrationPropertiesTest {

    @Test
    void legacyKmsDecryptRequiresKmsKeyIdWhenEnabled() {
        MigrationProperties properties = retryProperties();
        properties.getDecrypt().getLegacyKms().setEnabled(true);

        assertThatThrownBy(properties::validateForRun)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("migration.decrypt.legacy-kms.kms-key-id");
    }

    @Test
    void legacyKmsDecryptValidationPassesWithKmsKeyId() {
        MigrationProperties properties = retryProperties();
        properties.getDecrypt().getLegacyKms().setEnabled(true);
        properties.getDecrypt().getLegacyKms().setKmsKeyId("arn:aws:kms:us-east-1:111122223333:key/example");

        assertThatCode(properties::validateForRun).doesNotThrowAnyException();
    }

    private static MigrationProperties retryProperties() {
        MigrationProperties properties = new MigrationProperties();
        properties.getJob().setMode(JobMode.RETRY);
        properties.getS3().setSourceBucket("source");
        properties.getS3().setTargetBucket("target");
        properties.getPaths().setRetryInputs(List.of(Path.of("failed.log")));
        return properties;
    }
}
