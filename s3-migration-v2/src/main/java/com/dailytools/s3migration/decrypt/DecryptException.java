package com.dailytools.s3migration.decrypt;

public class DecryptException extends Exception {

    public DecryptException(String message) {
        super(message);
    }

    public DecryptException(String message, Throwable cause) {
        super(message, cause);
    }
}
