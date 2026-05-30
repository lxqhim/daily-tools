package com.dailytools.s3migration.inventory;

@FunctionalInterface
public interface InventoryRowParseFailureHandler {

    void handle(InventoryRowParseFailure failure) throws Exception;
}
