package com.dailytools.s3migration.failed;

import java.nio.file.Path;

public class FailedLogLimitExceededException extends IllegalStateException {

    public FailedLogLimitExceededException(Path path, long maxBytes, long attemptedBytes) {
        super("Failed log size limit exceeded for " + path + ": maxBytes=" + maxBytes + ", attemptedBytes="
                + attemptedBytes);
    }
}
