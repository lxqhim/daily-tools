package com.dailytools.s3migration.inventory;

import java.nio.charset.StandardCharsets;

public final class S3InventoryKeyDecoder {

    private S3InventoryKeyDecoder() {}

    public static String decode(String encodedKey) {
        int firstPercent = encodedKey.indexOf('%');
        if (firstPercent < 0) {
            return encodedKey;
        }

        StringBuilder decoded = new StringBuilder(encodedKey.length());
        decoded.append(encodedKey, 0, firstPercent);
        byte[] bytes = new byte[encodedKey.length() - firstPercent];
        int index = firstPercent;
        while (index < encodedKey.length()) {
            char value = encodedKey.charAt(index);
            if (value == '%') {
                int byteCount = 0;
                while (index < encodedKey.length() && encodedKey.charAt(index) == '%') {
                    bytes[byteCount++] = decodePercentByte(encodedKey, index);
                    index += 3;
                }
                decoded.append(new String(bytes, 0, byteCount, StandardCharsets.UTF_8));
            } else {
                decoded.append(value);
                index++;
            }
        }
        return decoded.toString();
    }

    private static byte decodePercentByte(String encodedKey, int percentIndex) {
        if (percentIndex + 2 >= encodedKey.length()) {
            throw new IllegalArgumentException("Invalid percent encoding in S3 Inventory key");
        }
        int high = Character.digit(encodedKey.charAt(percentIndex + 1), 16);
        int low = Character.digit(encodedKey.charAt(percentIndex + 2), 16);
        if (high < 0 || low < 0) {
            throw new IllegalArgumentException("Invalid percent encoding in S3 Inventory key");
        }
        return (byte) ((high << 4) + low);
    }
}
