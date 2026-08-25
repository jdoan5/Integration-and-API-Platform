package com.jdoan.inventory.rest.api;

public record MovementResultDto(
        long movementId,
        String sku,
        String warehouseCode,
        int quantityBefore,
        int quantityAfter,
        int delta,              // computed convenience the SOAP contract does not provide
        String recordedAt,
        boolean replayed        // true when an Idempotency-Key returned a cached result
) {}
