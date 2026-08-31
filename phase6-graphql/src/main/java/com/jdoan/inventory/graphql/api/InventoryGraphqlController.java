package com.jdoan.inventory.graphql.api;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The resolvers.
 *
 * Note how little there is. In REST, adding "product name alongside stock" is a
 * new endpoint or a `?expand=` parameter someone has to design. Here it is a
 * field the client selects, and the cost of that convenience is that the server
 * no longer knows what any given request will cost - which is the problem the
 * rest of this phase is about.
 */
@Controller
public class InventoryGraphqlController {

    private final InventoryService service;

    public InventoryGraphqlController(InventoryService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ queries
    @QueryMapping
    public Types.Product product(@Argument String sku) {
        return service.getProduct(sku);
    }

    @QueryMapping
    public List<Types.LowStockItem> lowStock(@Argument String warehouse, @Argument Integer limit) {
        return service.listLowStock(WarehouseCodes.toDomain(warehouse), limit);
    }

    // ------------------------------------------------------------- nested fields
    /**
     * Stock for a product, resolved lazily.
     *
     * Not batched, on purpose: it takes an argument, and two items asking for
     * different warehouses cannot share a backend call. That is the real limit
     * of DataLoader - it batches by KEY, and an argument is part of the key.
     */
    @SchemaMapping(typeName = "Product", field = "stockLevels")
    public List<Types.StockLevel> stockLevels(Types.Product product, @Argument String warehouse) {
        return service.getStockLevels(product.sku(), WarehouseCodes.toDomain(warehouse));
    }

    /**
     * The batched resolver - one invocation for every LowStockItem in the result.
     *
     * WHAT BATCHING ACTUALLY BUYS YOU HERE, honestly: the SOAP service has no
     * bulk "get these products" operation, so N distinct SKUs still cost N
     * backend calls. What this removes is DUPLICATE work - the same SKU low in
     * three warehouses is fetched once, not three times - and it issues the
     * calls from one place instead of scattered through the resolution tree.
     *
     * It does NOT make an expensive query cheap. The honest fix for that is
     * either a bulk operation upstream, or refusing the query - which is why
     * QueryCostConfig exists. "Add a DataLoader" is the answer to duplication,
     * not to cost.
     *
     * THIS LOOP WAS A PARALLEL STREAM UNTIL PHASE 7 TRACED IT. The comment here
     * claimed the batch ran the fetches concurrently, and the trace showed six
     * strictly sequential SOAP spans with no pool threads anywhere near them -
     * `.parallel()` was decoration. It is gone rather than "fixed", because
     * genuine concurrency here needs an executor AND trace-context propagation
     * across the thread boundary, and six 3ms calls do not justify either.
     *
     * The point worth keeping: a counter said "7 backend calls" and was right,
     * and the code still described itself incorrectly. Only the waterfall
     * showed the shape.
     */
    @BatchMapping(typeName = "LowStockItem", field = "product")
    public Map<Types.LowStockItem, Types.Product> lowStockProducts(List<Types.LowStockItem> items) {
        Set<String> distinctSkus = items.stream()
                .map(Types.LowStockItem::sku)
                .collect(Collectors.toSet());

        Map<String, Types.Product> bySku = distinctSkus.stream()
                .map(service::getProduct)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toMap(Types.Product::sku, p -> p));

        Map<Types.LowStockItem, Types.Product> result = new LinkedHashMap<>();
        for (Types.LowStockItem item : items) {
            Types.Product product = bySku.get(item.sku());
            if (product != null) {
                result.put(item, product);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- mutations
    @MutationMapping
    public Types.MovementResult recordMovement(@Argument Types.MovementInput input) {
        return service.recordMovement(input);
    }
}
