package com.jdoan.inventory.rest.api;

public record StockLevelDto(
        String sku,
        String productName,
        String warehouseCode,
        String warehouseName,
        int quantity,
        int reorderPoint,
        boolean belowReorderPoint     // computed here, not present in the SOAP payload
) {}
