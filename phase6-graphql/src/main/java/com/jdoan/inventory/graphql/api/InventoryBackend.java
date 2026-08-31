package com.jdoan.inventory.graphql.api;

import java.util.List;

/**
 * Where inventory data comes from.
 *
 * WHY AN INTERFACE. Until Phase 7 this facade talked to the SOAP service and
 * nothing else, and that was fine - one backend, no abstraction earned. What
 * changed is that the platform needed a PUBLIC demo: a version anyone can open
 * and query without a database, a SOAP service, a gateway, or a laptop.
 *
 * The seam goes here rather than lower down on purpose. It is expressed in the
 * GRAPHQL types, not the JAXB ones, so an implementation is free to have never
 * heard of SOAP - which is what makes a fixture-backed implementation honest
 * rather than a mock of a mock.
 *
 * Everything above this line - caching, idempotency, batching, cost analysis -
 * is unchanged by which implementation is bound.
 */
public interface InventoryBackend {

    /** @return the product, or null when no such SKU exists. Absence is not an error. */
    Types.Product getProduct(String sku);

    /** @param warehouseCode domain form (WH-EAST), or null for every warehouse. */
    List<Types.StockLevel> getStockLevels(String sku, String warehouseCode);

    List<Types.LowStockItem> listLowStock(String warehouseCode, Integer limit);

    /** Records a movement. May refuse - a read-only backend says so here. */
    Types.MovementResult recordMovement(Types.MovementInput input);
}
