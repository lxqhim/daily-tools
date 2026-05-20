package com.dailytools.s3migration.shard;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

@Component
public class HashShardAssigner {

    public boolean owns(String key, int shardTotal, int shardIndex) {
        validate(shardTotal, shardIndex);
        return shardFor(key, shardTotal) == shardIndex;
    }

    public int shardFor(String key, int shardTotal) {
        if (shardTotal < 1) {
            throw new IllegalArgumentException("shardTotal must be positive");
        }
        byte[] digest = sha256(key.getBytes(StandardCharsets.UTF_8));
        return new BigInteger(1, digest).mod(BigInteger.valueOf(shardTotal)).intValue();
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
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
