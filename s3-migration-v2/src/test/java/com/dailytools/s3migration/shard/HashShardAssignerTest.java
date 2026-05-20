package com.dailytools.s3migration.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void rejectsInvalidShardIndex() {
        assertThatThrownBy(() -> assigner.owns("key", 4, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("range");
    }
}
