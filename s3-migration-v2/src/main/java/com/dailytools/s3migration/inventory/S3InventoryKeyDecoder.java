package com.dailytools.s3migration.inventory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class S3InventoryKeyDecoder {

    private S3InventoryKeyDecoder() {}

    public static String decode(String encodedKey) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(encodedKey.length());
        for (int index = 0; index < encodedKey.length(); index++) {
            char value = encodedKey.charAt(index);
            if (value == '%') {
                if (index + 2 >= encodedKey.length()) {
                    throw new IllegalArgumentException("Invalid percent encoding in S3 Inventory key");
                }
                int high = Character.digit(encodedKey.charAt(index + 1), 16);
                int low = Character.digit(encodedKey.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("Invalid percent encoding in S3 Inventory key");
                }
                bytes.write((high << 4) + low);
                index += 2;
            } else {
                byte[] utf8 = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                bytes.writeBytes(utf8);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
