package com.dailytools.s3migration.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class S3InventoryKeyDecoderTest {

    @Test
    void decodesPercentEncodedSpacesAndUtf8() {
        assertThat(S3InventoryKeyDecoder.decode("folder/a%20b/%E4%B8%AD%E6%96%87.txt"))
                .isEqualTo("folder/a b/中文.txt");
    }

    @Test
    void preservesPlusCharacter() {
        assertThat(S3InventoryKeyDecoder.decode("folder/a+b.txt")).isEqualTo("folder/a+b.txt");
        assertThat(S3InventoryKeyDecoder.decode("folder/a%2Bb.txt")).isEqualTo("folder/a+b.txt");
    }

    @Test
    void rejectsInvalidPercentEncoding() {
        assertThatThrownBy(() -> S3InventoryKeyDecoder.decode("folder/%ZZ.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
