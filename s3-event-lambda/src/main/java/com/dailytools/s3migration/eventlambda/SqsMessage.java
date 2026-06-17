package com.dailytools.s3migration.eventlambda;

record SqsMessage(String messageId, String body) {}
