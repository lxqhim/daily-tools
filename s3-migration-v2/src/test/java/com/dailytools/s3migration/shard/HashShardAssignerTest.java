package com.dailytools.s3migration.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.junit.jupiter.api.Test;

class HashShardAssignerTest {

    private final HashShardAssigner assigner = new HashShardAssigner();

    @Test
    void mapsKeyToExactlyOneShard() {
        String key = "folder/object.txt";
        int total = 8;
        int ownerCount = 0;
        int shard = assigner.shardFor(key, total);

        for (int index = 0; index < total; index++) {
            if (assigner.owns(key, total, index)) {
                ownerCount++;
            }
        }

        assertThat(ownerCount).isOne();
        assertThat(shard).isBetween(0, total - 1);
    }

    @Test
    void optimizedModuloMatchesLegacyBigIntegerMapping() throws Exception {
        List<String> keys = List.of("folder/object.txt", "folder/a+b,中文.txt", "empty", "prefix/2026/05/20/data");
        List<Integer> totals = List.of(1, 2, 8, 128, 1024);

        for (String key : keys) {
            for (int total : totals) {
                assertThat(assigner.shardFor(key, total)).isEqualTo(legacyShardFor(key, total));
            }
        }
    }

    @Test
    void rejectsInvalidShardIndex() {
        assertThatThrownBy(() -> assigner.owns("key", 4, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("range");
    }

    private static int legacyShardFor(String key, int shardTotal) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        return new BigInteger(1, digest).mod(BigInteger.valueOf(shardTotal)).intValue();
    }
}
