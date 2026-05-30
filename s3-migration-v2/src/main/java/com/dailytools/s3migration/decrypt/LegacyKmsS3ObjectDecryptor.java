package com.dailytools.s3migration.decrypt;

import com.amazonaws.services.s3.AmazonS3Encryption;
import com.amazonaws.services.s3.AmazonS3EncryptionClientBuilder;
import com.amazonaws.services.s3.model.CryptoConfiguration;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.KMSEncryptionMaterialsProvider;
import com.dailytools.s3migration.config.MigrationProperties;
import com.dailytools.s3migration.crypto.AwsCryptoConfigurationFactory;
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
        return AwsCryptoConfigurationFactory.create(
                legacyKms.getCryptoMode(), legacyKms.getStorageMode(), kmsRegion(properties));
    }

    private static String kmsRegion(MigrationProperties properties) {
        String configuredKmsRegion = properties.getDecrypt().getLegacyKms().getKmsRegion();
        if (configuredKmsRegion != null && !configuredKmsRegion.isBlank()) {
            return configuredKmsRegion;
        }
        return properties.getS3().getRegion();
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
