package com.jdoan.inventory.soap;

import com.jdoan.inventory.soap.generated.LowStockItemType;
import com.jdoan.inventory.soap.generated.ProductType;
import com.jdoan.inventory.soap.generated.StockLevelType;
import com.jdoan.inventory.soap.repository.InventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.ws.test.server.MockWebServiceClient;
import org.springframework.xml.transform.StringSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.ws.test.server.RequestCreators.withPayload;
import static org.springframework.ws.test.server.ResponseMatchers.*;

/**
 * ENDPOINT TESTS using MockWebServiceClient.
 *
 * These send real SOAP payloads through the full Spring-WS pipeline -
 * including the validating interceptor and the fault resolver - WITHOUT
 * needing a running server or a database. The repository is mocked, so the
 * tests are fast and run anywhere (CI included).
 *
 * That last point matters: a test suite that needs your laptop's Postgres
 * running is a test suite that will not run in CI.
 */
@SpringBootTest
class InventoryEndpointTest {

    private static final String NS = "http://jdoan.com/inventory/v1";

    @Autowired
    private ApplicationContext applicationContext;

    @MockitoBean
    private InventoryRepository repository;

    private MockWebServiceClient client;

    @BeforeEach
    void setUp() {
        client = MockWebServiceClient.createClient(applicationContext);
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("GetProduct returns the product payload")
    void getProduct() {
        ProductType p = new ProductType();
        p.setSku("ELEC-LAP-001");
        p.setName("UltraBook 14\"");
        p.setUnitPrice(new BigDecimal("1299.00"));
        p.setUnitCost(new BigDecimal("850.00"));
        p.setReorderPoint(15);
        p.setReorderQuantity(40);
        p.setActive(true);
        when(repository.findProductBySku("ELEC-LAP-001")).thenReturn(Optional.of(p));

        client.sendRequest(withPayload(new StringSource("""
                        <inv:GetProductRequest xmlns:inv="%s">
                            <inv:sku>ELEC-LAP-001</inv:sku>
                        </inv:GetProductRequest>
                        """.formatted(NS))))
                .andExpect(noFault())
                .andExpect(xpath("//*[local-name()='sku']").evaluatesTo("ELEC-LAP-001"))
                .andExpect(xpath("//*[local-name()='unitPrice']").evaluatesTo("1299.00"));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("GetStockLevel returns one entry per warehouse")
    void getStockLevelAcrossWarehouses() {
        when(repository.skuExists("ELEC-AUD-001")).thenReturn(true);
        when(repository.findStockLevels(eq("ELEC-AUD-001"), any()))
                .thenReturn(List.of(stock("WH-CENT", 60), stock("WH-EAST", 140)));

        client.sendRequest(withPayload(new StringSource("""
                        <inv:GetStockLevelRequest xmlns:inv="%s">
                            <inv:sku>ELEC-AUD-001</inv:sku>
                        </inv:GetStockLevelRequest>
                        """.formatted(NS))))
                .andExpect(noFault())
                .andExpect(xpath("count(//*[local-name()='stockLevel'])").evaluatesTo(2));
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("ListLowStock reports items and a total count")
    void listLowStock() {
        LowStockItemType item = new LowStockItemType();
        item.setSku("FURN-DSK-002");
        item.setProductName("Compact Writing Desk");
        item.setWarehouseCode("WH-CENT");
        item.setQuantity(0);
        item.setReorderPoint(12);
        item.setDeficit(12);
        item.setSuggestedOrderQty(30);

        when(repository.findLowStock(any(), any())).thenReturn(List.of(item));
        when(repository.countLowStock(any())).thenReturn(7);

        client.sendRequest(withPayload(new StringSource("""
                        <inv:ListLowStockRequest xmlns:inv="%s">
                            <inv:maxResults>10</inv:maxResults>
                        </inv:ListLowStockRequest>
                        """.formatted(NS))))
                .andExpect(noFault())
                .andExpect(xpath("//*[local-name()='totalCount']").evaluatesTo("7"));
    }

    // ------------------------------------------------------------------
    /**
     * The behavioural contract of the write path: the service reports the
     * quantity BEFORE and AFTER, and the difference comes from the database
     * trigger - not from any update this service issues.
     */
    @Test
    @DisplayName("RecordStockMovement reports before/after quantities")
    void recordStockMovement() {
        when(repository.skuExists("ELEC-AUD-001")).thenReturn(true);
        when(repository.warehouseExists("WH-EAST")).thenReturn(true);
        when(repository.currentQuantity("ELEC-AUD-001", "WH-EAST"))
                .thenReturn(Optional.of(140))   // before
                .thenReturn(Optional.of(160));  // after the trigger fired
        when(repository.insertMovement(any(), any(), any(), any(Integer.class), any(), any()))
                .thenReturn(99L);

        client.sendRequest(withPayload(new StringSource("""
                        <inv:RecordStockMovementRequest xmlns:inv="%s">
                            <inv:sku>ELEC-AUD-001</inv:sku>
                            <inv:warehouseCode>WH-EAST</inv:warehouseCode>
                            <inv:movementType>IN</inv:movementType>
                            <inv:quantity>20</inv:quantity>
                        </inv:RecordStockMovementRequest>
                        """.formatted(NS))))
                .andExpect(noFault())
                .andExpect(xpath("//*[local-name()='quantityBefore']").evaluatesTo("140"))
                .andExpect(xpath("//*[local-name()='quantityAfter']").evaluatesTo("160"))
                .andExpect(xpath("//*[local-name()='movementId']").evaluatesTo("99"));
    }

    // ==================================================================
    // SCHEMA VALIDATION - the requests below never reach endpoint code.
    // ==================================================================

    @Test
    @DisplayName("a SKU breaking the xs:pattern is rejected as a Client fault")
    void invalidSkuRejected() {
        client.sendRequest(withPayload(new StringSource("""
                        <inv:GetProductRequest xmlns:inv="%s">
                            <inv:sku>not-a-valid-sku</inv:sku>
                        </inv:GetProductRequest>
                        """.formatted(NS))))
                .andExpect(clientOrSenderFault());
    }

    @Test
    @DisplayName("a movement type outside the enumeration is rejected")
    void invalidMovementTypeRejected() {
        client.sendRequest(withPayload(new StringSource("""
                        <inv:RecordStockMovementRequest xmlns:inv="%s">
                            <inv:sku>ELEC-AUD-001</inv:sku>
                            <inv:warehouseCode>WH-EAST</inv:warehouseCode>
                            <inv:movementType>TELEPORT</inv:movementType>
                            <inv:quantity>5</inv:quantity>
                        </inv:RecordStockMovementRequest>
                        """.formatted(NS))))
                .andExpect(clientOrSenderFault());
    }

    @Test
    @DisplayName("a negative quantity is rejected by minExclusive")
    void negativeQuantityRejected() {
        client.sendRequest(withPayload(new StringSource("""
                        <inv:RecordStockMovementRequest xmlns:inv="%s">
                            <inv:sku>ELEC-AUD-001</inv:sku>
                            <inv:warehouseCode>WH-EAST</inv:warehouseCode>
                            <inv:movementType>IN</inv:movementType>
                            <inv:quantity>-5</inv:quantity>
                        </inv:RecordStockMovementRequest>
                        """.formatted(NS))))
                .andExpect(clientOrSenderFault());
    }

    // ==================================================================
    // BUSINESS FAULTS - schema-valid, but the resource does not exist.
    // ==================================================================

    @Test
    @DisplayName("an unknown SKU produces a structured InventoryError detail")
    void unknownSkuProducesStructuredFault() {
        when(repository.findProductBySku("ELEC-LAP-999")).thenReturn(Optional.empty());

        client.sendRequest(withPayload(new StringSource("""
                        <inv:GetProductRequest xmlns:inv="%s">
                            <inv:sku>ELEC-LAP-999</inv:sku>
                        </inv:GetProductRequest>
                        """.formatted(NS))))
                .andExpect(clientOrSenderFault())
                .andExpect(xpath("//*[local-name()='code']").evaluatesTo("PRODUCT_NOT_FOUND"))
                .andExpect(xpath("//*[local-name()='field']").evaluatesTo("sku"));
    }

    // ------------------------------------------------------------------
    private StockLevelType stock(String warehouse, int qty) {
        StockLevelType s = new StockLevelType();
        s.setSku("ELEC-AUD-001");
        s.setProductName("NoiseCancel Headphones");
        s.setWarehouseCode(warehouse);
        s.setWarehouseName(warehouse + " DC");
        s.setQuantity(qty);
        s.setReorderPoint(25);
        return s;
    }
}
