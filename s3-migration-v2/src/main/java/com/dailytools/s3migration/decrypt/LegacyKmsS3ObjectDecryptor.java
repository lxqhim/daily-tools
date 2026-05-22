package com.dailytools.s3migration.decrypt;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3Encryption;
import com.amazonaws.services.s3.AmazonS3EncryptionClientBuilder;
import com.amazonaws.services.s3.model.CryptoConfiguration;
import com.amazonaws.services.s3.model.CryptoMode;
import com.amazonaws.services.s3.model.CryptoStorageMode;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.KMSEncryptionMaterialsProvider;
import com.dailytools.s3migration.config.MigrationProperties;
import java.nio.file.Files;
import java.nio.file.Path;

public class LegacyKmsS3ObjectDecryptor implements S3ObjectDecryptor {

    private final AmazonS3Encryption encryptedS3;
    private final Path tempDir;

    public LegacyKmsS3ObjectDecryptor(MigrationProperties properties) {
        MigrationProperties.LegacyKms legacyKms = properties.getDecrypt().getLegacyKms();
        this.tempDir = properties.getPaths().getTempDir();
        createTempDirectory(tempDir);
        this.encryptedS3 = AmazonS3EncryptionClientBuilder.standard()
                .withRegion(properties.getS3().getRegion())
                .withCryptoConfiguration(cryptoConfiguration(properties))
                .withEncryptionMaterials(new KMSEncryptionMaterialsProvider(legacyKms.getKmsKeyId()))
                .build();
    }

    @Override
    public Path decryptToLocal(String bucketName, String prefix) throws DecryptException {
        Path localFile = null;
        try {
            localFile = Files.createTempFile(tempDir, "s3-decrypt-", ".bin");
            encryptedS3.getObject(new GetObjectRequest(bucketName, prefix), localFile.toFile());
            return localFile;
        } catch (Exception exception) {
            cleanupPartialFile(localFile);
            throw new DecryptException("Failed to decrypt source object " + bucketName + "/" + prefix, exception);
        }
    }

    private static void createTempDirectory(Path tempDir) {
        try {
            Files.createDirectories(tempDir);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create decrypt temp directory " + tempDir, exception);
        }
    }

    private static CryptoConfiguration cryptoConfiguration(MigrationProperties properties) {
        MigrationProperties.LegacyKms legacyKms = properties.getDecrypt().getLegacyKms();
        CryptoConfiguration configuration = new CryptoConfiguration()
                .withCryptoMode(cryptoMode(legacyKms.getCryptoMode()))
                .withStorageMode(storageMode(legacyKms.getStorageMode()));
        configuration.withAwsKmsRegion(Region.getRegion(Regions.fromName(kmsRegion(properties))));
        return configuration;
    }

    private static String kmsRegion(MigrationProperties properties) {
        String configuredKmsRegion = properties.getDecrypt().getLegacyKms().getKmsRegion();
        if (configuredKmsRegion != null && !configuredKmsRegion.isBlank()) {
            return configuredKmsRegion;
        }
        return properties.getS3().getRegion();
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

    private static void cleanupPartialFile(Path localFile) {
        if (localFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(localFile);
        } catch (Exception ignored) {
            // The caller will record the decrypt failure; partial-file cleanup is best effort here.
        }
    }
}
