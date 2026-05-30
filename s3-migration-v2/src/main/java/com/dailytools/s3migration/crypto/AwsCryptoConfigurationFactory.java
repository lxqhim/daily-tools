package com.dailytools.s3migration.crypto;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.model.CryptoConfiguration;
import com.amazonaws.services.s3.model.CryptoMode;
import com.amazonaws.services.s3.model.CryptoStorageMode;
import com.dailytools.s3migration.config.MigrationProperties;

public final class AwsCryptoConfigurationFactory {

    private AwsCryptoConfigurationFactory() {}

    public static CryptoConfiguration create(
            MigrationProperties.LegacyKmsCryptoMode cryptoMode,
            MigrationProperties.LegacyKmsStorageMode storageMode,
            String kmsRegion) {
        CryptoConfiguration configuration = new CryptoConfiguration()
                .withCryptoMode(cryptoMode(cryptoMode))
                .withStorageMode(storageMode(storageMode));
        configuration.withAwsKmsRegion(Region.getRegion(Regions.fromName(kmsRegion)));
        return configuration;
    }

    private static CryptoMode cryptoMode(MigrationProperties.LegacyKmsCryptoMode mode) {
        return switch (mode) {
            case ENCRYPTION_ONLY -> CryptoMode.EncryptionOnly;
            case AUTHENTICATED_ENCRYPTION -> CryptoMode.AuthenticatedEncryption;
            case STRICT_AUTHENTICATED_ENCRYPTION -> CryptoMode.StrictAuthenticatedEncryption;
        };
    }

    private static CryptoStorageMode storageMode(MigrationProperties.LegacyKmsStorageMode mode) {
        return switch (mode) {
            case OBJECT_METADATA -> CryptoStorageMode.ObjectMetadata;
            case INSTRUCTION_FILE -> CryptoStorageMode.InstructionFile;
        };
    }
}
