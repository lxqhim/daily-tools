package com.dailytools.s3migration.config;

import com.dailytools.s3migration.decrypt.MissingS3ObjectDecryptor;
import com.dailytools.s3migration.decrypt.S3ObjectDecryptor;
import com.dailytools.s3migration.s3.AwsS3ObjectReader;
import com.dailytools.s3migration.s3.S3ObjectReader;
import com.dailytools.s3migration.upload.S3TargetUploader;
import com.dailytools.s3migration.upload.TargetUploader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class AwsConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    @ConditionalOnMissingBean
    S3Client s3Client(MigrationProperties properties) {
        return S3Client.builder()
                .region(Region.of(properties.getS3().getRegion()))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    S3ObjectReader s3ObjectReader(S3Client s3Client) {
        return new AwsS3ObjectReader(s3Client);
    }

    @Bean
    @ConditionalOnMissingBean
    TargetUploader targetUploader(S3Client s3Client, MigrationProperties properties) {
        return new S3TargetUploader(s3Client, properties.getUpload());
    }

    @Bean
    @ConditionalOnMissingBean
    S3ObjectDecryptor s3ObjectDecryptor() {
        return new MissingS3ObjectDecryptor();
    }
}
