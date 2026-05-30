package com.dailytools.s3migration.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dailytools.s3migration.job.JobMode;
import java.nio.file.Path;
import java.time.Duration;
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

    @Test
    void clientSideKmsUploadRequiresKmsKeyArnWhenEnabled() {
        MigrationProperties properties = retryProperties();
        properties.getUpload().getClientSideKms().setEnabled(true);

        assertThatThrownBy(properties::validateForRun)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("migration.upload.client-side-kms.kms-key-id");

        properties.getUpload().getClientSideKms().setKmsKeyId("alias/target-key");

        assertThatThrownBy(properties::validateForRun)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a full KMS key ARN");
    }

    @Test
    void clientSideKmsUploadValidationPassesWithKmsKeyArn() {
        MigrationProperties properties = retryProperties();
        properties.getUpload().getClientSideKms().setEnabled(true);
        properties.getUpload()
                .getClientSideKms()
                .setKmsKeyId("arn:aws:kms:us-east-1:444455556666:key/target-key-id");

        assertThatCode(properties::validateForRun).doesNotThrowAnyException();
    }

    @Test
    void deltaLookbackMustNotBeNegative() {
        MigrationProperties properties = retryProperties();
        properties.getJob().setDeltaLookback(Duration.ofHours(-1));

        assertThatThrownBy(properties::validateForRun)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("migration.job.delta-lookback");
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
