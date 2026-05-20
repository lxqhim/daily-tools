package com.dailytools.s3migration;

import com.dailytools.s3migration.config.MigrationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MigrationProperties.class)
public class S3MigrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(S3MigrationApplication.class, args);
    }
}
