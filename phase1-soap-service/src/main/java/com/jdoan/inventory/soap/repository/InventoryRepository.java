package com.jdoan.inventory.soap.repository;

import com.jdoan.inventory.soap.generated.LowStockItemType;
import com.jdoan.inventory.soap.generated.ProductType;
import com.jdoan.inventory.soap.generated.StockLevelType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Data access against the inventory_mgmt database from the SQL project.
 *
 * Note how little SQL this needs: the heavy lifting already lives in the
 * database as VIEWS (v_current_stock, v_low_stock_items). That is the payoff
 * of having built those - the service layer stays thin, and the business rule
 * "what counts as low stock" has exactly one definition, in one place.
 */
@Repository
public class InventoryRepository {

    private final JdbcClient db;

    public InventoryRepository(JdbcClient db) {
        this.db = db;
    }

    // ------------------------------------------------------------------
    // GetProduct
    // ------------------------------------------------------------------
    public Optional<ProductType> findProductBySku(String sku) {
        return db.sql("""
                    SELECT p.sku, p.name, p.description, c.name AS category,
                           p.unit_price, p.unit_cost,
                           p.reorder_point, p.reorder_quantity, p.is_active
                    FROM products p
                    LEFT JOIN categories c ON c.category_id = p.category_id
                    WHERE p.sku = :sku
                """)
                .param("sku", sku)
                .query((rs, n) -> {
                    ProductType p = new ProductType();
                    p.setSku(rs.getString("sku"));
                    p.setName(rs.getString("name"));
                    p.setDescription(rs.getString("description"));
                    p.setCategory(rs.getString("category"));
                    p.setUnitPrice(rs.getBigDecimal("unit_price"));
                    p.setUnitCost(rs.getBigDecimal("unit_cost"));
                    p.setReorderPoint(rs.getInt("reorder_point"));
                    p.setReorderQuantity(rs.getInt("reorder_quantity"));
                    p.setActive(rs.getBoolean("is_active"));
                    return p;
                })
                .optional();
    }

    // ------------------------------------------------------------------
    // GetStockLevel  -  warehouseCode is optional (null = all warehouses)
    // ------------------------------------------------------------------
    public List<StockLevelType> findStockLevels(String sku, String warehouseCode) {
        return db.sql("""
                    SELECT sku, product_name, warehouse_code, warehouse_name,
                           quantity, reorder_point, last_updated
                    FROM v_current_stock
                    WHERE sku = :sku
                      AND (CAST(:wh AS text) IS NULL OR warehouse_code = CAST(:wh AS text))
                    ORDER BY warehouse_code
                """)
                .param("sku", sku)
                .param("wh", warehouseCode)
                .query((rs, n) -> {
                    StockLevelType s = new StockLevelType();
                    s.setSku(rs.getString("sku"));
                    s.setProductName(rs.getString("product_name"));
                    s.setWarehouseCode(rs.getString("warehouse_code"));
                    s.setWarehouseName(rs.getString("warehouse_name"));
                    s.setQuantity(rs.getInt("quantity"));
                    s.setReorderPoint(rs.getInt("reorder_point"));
                    return s;
                })
                .list();
    }

    // ------------------------------------------------------------------
    // ListLowStock  -  straight off the view you already built
    // ------------------------------------------------------------------
    public List<LowStockItemType> findLowStock(String warehouseCode, Integer maxResults) {
        int limit = (maxResults == null || maxResults <= 0) ? 100 : maxResults;
        return db.sql("""
                    SELECT sku, product_name, warehouse_code, quantity,
                           reorder_point, deficit, suggested_order_qty
                    FROM v_low_stock_items
                    WHERE (CAST(:wh AS text) IS NULL OR warehouse_code = CAST(:wh AS text))
                    ORDER BY deficit DESC
                    LIMIT :lim
                """)
                .param("wh", warehouseCode)
                .param("lim", limit)
                .query((rs, n) -> {
                    LowStockItemType i = new LowStockItemType();
                    i.setSku(rs.getString("sku"));
                    i.setProductName(rs.getString("product_name"));
                    i.setWarehouseCode(rs.getString("warehouse_code"));
                    i.setQuantity(rs.getInt("quantity"));
                    i.setReorderPoint(rs.getInt("reorder_point"));
                    i.setDeficit(rs.getInt("deficit"));
                    i.setSuggestedOrderQty(rs.getInt("suggested_order_qty"));
                    return i;
                })
                .list();
    }

    /**
     * Note the CASTs here and in the two queries above.
     *
     * Postgres cannot infer a parameter's type when it appears only inside an
     * IS NULL test - the server rejects the statement with "could not determine
     * data type of parameter $1". Wrapping it in CAST(... AS text) tells the
     * planner what it is. This bites every optional-filter query written this
     * way against Postgres, and the error message never mentions IS NULL.
     */
    public int countLowStock(String warehouseCode) {
        return db.sql("SELECT COUNT(*) FROM v_low_stock_items WHERE (CAST(:wh AS text) IS NULL OR warehouse_code = CAST(:wh AS text))")
                .param("wh", warehouseCode)
                .query(Integer.class)
                .single();
    }

    // ------------------------------------------------------------------
    // RecordStockMovement  -  the write path
    // ------------------------------------------------------------------
    public Optional<Integer> currentQuantity(String sku, String warehouseCode) {
        return db.sql("""
                    SELECT sl.quantity
                    FROM stock_levels sl
                    JOIN products   p ON p.product_id   = sl.product_id
                    JOIN warehouses w ON w.warehouse_id = sl.warehouse_id
                    WHERE p.sku = :sku AND w.code = :wh
                """)
                .param("sku", sku)
                .param("wh", warehouseCode)
                .query(Integer.class)
                .optional();
    }

    public boolean skuExists(String sku) {
        return db.sql("SELECT COUNT(*) FROM products WHERE sku = :sku")
                .param("sku", sku).query(Integer.class).single() > 0;
    }

    public boolean warehouseExists(String code) {
        return db.sql("SELECT COUNT(*) FROM warehouses WHERE code = :code")
                .param("code", code).query(Integer.class).single() > 0;
    }

    /**
     * Inserts ONE row into the stock_movements ledger and returns its id.
     *
     * Deliberately does NOT touch stock_levels. The trg_stock_movement_apply
     * trigger from 05_triggers.sql does that. This service records the EVENT;
     * the database derives the CONSEQUENCE.
     */
    public long insertMovement(String sku, String warehouseCode, String movementType,
                               int quantity, String referenceType, String notes) {
        return db.sql("""
                    INSERT INTO stock_movements
                        (product_id, warehouse_id, movement_type, quantity, reference_type, notes)
                    SELECT p.product_id, w.warehouse_id, CAST(:mt AS movement_type_enum),
                           :qty, :ref, :notes
                    FROM products p, warehouses w
                    WHERE p.sku = :sku AND w.code = :wh
                    RETURNING movement_id
                """)
                .param("sku", sku)
                .param("wh", warehouseCode)
                .param("mt", movementType)
                .param("qty", quantity)
                .param("ref", referenceType == null ? "SOAP" : referenceType)
                .param("notes", notes)
                .query(Long.class)
                .single();
    }
}
