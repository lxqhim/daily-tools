package com.dailytools.s3migration.shard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

@Component
public class HashShardAssigner {

    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(HashShardAssigner::newSha256);

    public boolean owns(String key, int shardTotal, int shardIndex) {
        validate(shardTotal, shardIndex);
        return shardFor(key, shardTotal) == shardIndex;
    }

    public int shardFor(String key, int shardTotal) {
        if (shardTotal < 1) {
            throw new IllegalArgumentException("shardTotal must be positive");
        }
        byte[] digest = sha256(key.getBytes(StandardCharsets.UTF_8));
        return positiveModulo(digest, shardTotal);
    }

    private static void validate(int shardTotal, int shardIndex) {
        if (shardTotal < 1) {
            throw new IllegalArgumentException("shardTotal must be positive");
        }
        if (shardIndex < 0 || shardIndex >= shardTotal) {
            throw new IllegalArgumentException("shardIndex must be in range");
        }
    }

    private static byte[] sha256(byte[] input) {
        MessageDigest digest = SHA_256.get();
        digest.reset();
        return digest.digest(input);
    }

    private static int positiveModulo(byte[] unsignedBigEndian, int divisor) {
        int modulo = 0;
        for (byte value : unsignedBigEndian) {
            modulo = (int) ((((long) modulo) * 256 + (value & 0xff)) % divisor);
        }
        return modulo;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
