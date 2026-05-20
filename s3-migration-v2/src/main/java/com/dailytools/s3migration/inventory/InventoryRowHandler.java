package com.dailytools.s3migration.inventory;

@FunctionalInterface
public interface InventoryRowHandler {

    void handle(InventoryObject object) throws Exception;
}
