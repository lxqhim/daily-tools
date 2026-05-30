package com.dailytools.s3migration.config;

import com.dailytools.s3migration.decrypt.S3ObjectDecryptor;
import com.dailytools.s3migration.failed.FailedLog;
import com.dailytools.s3migration.job.ObjectProcessor;
import com.dailytools.s3migration.upload.TargetUploader;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MigrationConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ObjectProcessor objectProcessor(
            ObjectProvider<S3ObjectDecryptor> decryptorProvider,
            TargetUploader uploader,
            FailedLog failedLog,
            MigrationProperties properties) {
        S3ObjectDecryptor decryptor = decryptorProvider.getIfAvailable();
        if (decryptor == null) {
            throw new BeanCreationException(
                    "No S3ObjectDecryptor configured. Set migration.decrypt.legacy-kms.enabled=true with "
                            + "migration.decrypt.legacy-kms.kms-key-id, or provide your own S3ObjectDecryptor bean.");
        }
        return new ObjectProcessor(decryptor, uploader, failedLog, properties);
    }
}
