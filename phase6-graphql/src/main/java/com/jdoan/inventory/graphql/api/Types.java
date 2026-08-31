package com.jdoan.inventory.graphql.api;

import java.util.List;

/**
 * The GraphQL types, as records.
 *
 * Deliberately separate from the JAXB classes generated from the XSD, for the
 * same reason Phase 2's DTOs are: exposing generated SOAP types would couple
 * the public schema to the provider's XSD, and every consumer would feel the
 * next schema edit.
 */
public final class Types {

    private Types() {}

    public record Product(
            String sku, String name, String description, String category,
            double unitPrice, double unitCost,
            int reorderPoint, int reorderQuantity, boolean active) {}

    public record StockLevel(
            String sku, String productName, String warehouseCode, String warehouseName,
            int quantity, int reorderPoint, boolean belowReorderPoint) {}

    /**
     * Note this carries `sku` but NOT the product. The `product` field is
     * resolved separately and lazily - that is what makes it an N+1 and what
     * makes it batchable.
     */
    public record LowStockItem(
            String sku, String productName, String warehouseCode,
            int quantity, int reorderPoint, int deficit, int suggestedOrderQty) {}

    public record MovementResult(
            String movementId, String sku, String warehouseCode,
            int quantityBefore, int quantityAfter, int delta,
            String recordedAt, boolean replayed) {}

    public record MovementInput(
            String sku, String warehouseCode, String movementType, int quantity,
            String referenceType, String notes, String idempotencyKey) {}

    public record StockLevels(List<StockLevel> levels) {}
}
