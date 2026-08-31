package com.jdoan.inventory.graphql.api;

import com.jdoan.inventory.graphql.soapclient.BackendCallCounter;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The backend behind the public demo: a fixture, in memory, no dependencies.
 *
 * WHY THIS EXISTS. Everything else in this repo needs PostgreSQL, a SOAP
 * service, Redis, Kafka and a gateway before it answers a single question. That
 * is the right shape for the project and the wrong shape for a link someone
 * clicks once. This profile makes the GraphQL schema explorable on its own -
 * one container, no database, scale-to-zero - so the contract can be read and
 * queried by anyone.
 *
 * WHAT IS REAL AND WHAT IS NOT, stated plainly because a demo that blurs this
 * is worse than no demo:
 *   - The SCHEMA is real. Same .graphqls file, same resolvers, same validation.
 *   - The COST ANALYSIS is real. Same instrumentation, same complexity scores.
 *   - The N+1 is real. This class calls the counter exactly where the SOAP
 *     client does, so `lowStock { product { ... } }` still measures 1 vs 7.
 *   - The DATA is a snapshot, exported from the real inventory_mgmt database
 *     (15 products, 45 stock rows, 3 warehouses) into demo-inventory.json.
 *   - The WRITE is refused. It has nowhere to go, and pretending otherwise
 *     would be the one dishonest thing here.
 */
@Component
@Profile("demo")
public class DemoInventoryBackend implements InventoryBackend {

    private final BackendCallCounter counter;
    private final Map<String, Types.Product> products;
    private final List<Types.StockLevel> stock;

    public DemoInventoryBackend(BackendCallCounter counter, ObjectMapper json) throws IOException {
        this.counter = counter;

        Fixture fixture;
        try (InputStream in = new ClassPathResource("demo-inventory.json").getInputStream()) {
            fixture = json.readValue(in, Fixture.class);
        }

        this.products = fixture.products().stream()
                .collect(Collectors.toMap(Types.Product::sku, Function.identity(),
                        (a, b) -> a, java.util.LinkedHashMap::new));

        // belowReorderPoint is computed, exactly as the SOAP mapping computes it,
        // rather than stored - so the two implementations cannot drift on it.
        this.stock = fixture.stock().stream()
                .map(r -> new Types.StockLevel(
                        r.sku(), r.productName(),
                        WarehouseCodes.toGraphql(r.warehouseCode()), r.warehouseName(),
                        r.quantity(), r.reorderPoint(),
                        r.quantity() < r.reorderPoint()))
                .toList();
    }

    // ------------------------------------------------------------------
    @Override
    public Types.Product getProduct(String sku) {
        counter.record("GetProduct");
        return products.get(sku);   // null for an unknown SKU, same as a NOT_FOUND fault
    }

    @Override
    public List<Types.StockLevel> getStockLevels(String sku, String warehouseCode) {
        counter.record("GetStockLevel");
        String wanted = WarehouseCodes.toGraphql(warehouseCode);
        return stock.stream()
                .filter(s -> s.sku().equals(sku))
                .filter(s -> wanted == null || s.warehouseCode().equals(wanted))
                .toList();
    }

    @Override
    public List<Types.LowStockItem> listLowStock(String warehouseCode, Integer limit) {
        counter.record("ListLowStock");
        String wanted = WarehouseCodes.toGraphql(warehouseCode);
        return stock.stream()
                .filter(Types.StockLevel::belowReorderPoint)
                .filter(s -> wanted == null || s.warehouseCode().equals(wanted))
                .sorted(Comparator.comparingInt((Types.StockLevel s) -> s.quantity() - s.reorderPoint()))
                .limit(limit == null || limit <= 0 ? 50 : limit)
                .map(s -> {
                    Types.Product p = products.get(s.sku());
                    return new Types.LowStockItem(
                            s.sku(), s.productName(), s.warehouseCode(),
                            s.quantity(), s.reorderPoint(),
                            s.reorderPoint() - s.quantity(),
                            p == null ? 0 : p.reorderQuantity());
                })
                .toList();
    }

    @Override
    public Types.MovementResult recordMovement(Types.MovementInput input) {
        throw new DemoReadOnlyException(
                "This is the public read-only demo, so recordMovement is refused - there is no "
                + "database behind it to write to. The mutation is left in the schema on purpose: "
                + "the contract is the real one, and hiding the write would misrepresent it. "
                + "Run the full stack from the repository to exercise it, including the "
                + "idempotencyKey that makes a retry safe.");
    }

    // ------------------------------------------------------------------
    /** Shapes matching demo-inventory.json, which is generated from the real database. */
    record Fixture(List<Types.Product> products, List<StockRow> stock) {}

    record StockRow(String sku, String productName, String warehouseCode, String warehouseName,
                    int quantity, int reorderPoint) {}
}
