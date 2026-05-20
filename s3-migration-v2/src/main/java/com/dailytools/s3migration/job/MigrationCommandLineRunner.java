package com.dailytools.s3migration.job;

import com.dailytools.s3migration.config.MigrationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "migration", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MigrationCommandLineRunner implements CommandLineRunner {

    private final MigrationProperties properties;
    private final MigrationService migrationService;

    public MigrationCommandLineRunner(MigrationProperties properties, MigrationService migrationService) {
        this.properties = properties;
        this.migrationService = migrationService;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!properties.isEnabled()) {
            return;
        }
        properties.validateForRun();
        migrationService.run();
    }
}
