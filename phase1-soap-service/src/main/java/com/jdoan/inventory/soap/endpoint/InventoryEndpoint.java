package com.jdoan.inventory.soap.endpoint;

import com.jdoan.inventory.soap.error.NotFoundException;
import com.jdoan.inventory.soap.generated.*;
import com.jdoan.inventory.soap.repository.InventoryRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

import java.time.ZonedDateTime;
import java.util.GregorianCalendar;
import java.util.List;

import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

/**
 * The SOAP endpoint.
 *
 * Every handler is matched by @PayloadRoot on {namespace}LocalPart - Spring-WS
 * routes by the XML element name of the request body, not by URL. That is a
 * genuine difference from REST worth internalising: one endpoint URL (/ws),
 * many operations, dispatched on payload.
 *
 * Note what is NOT here: no validation code. The PayloadValidatingInterceptor
 * already rejected malformed SKUs, negative quantities and unknown movement
 * types against the XSD. Everything reaching these methods is schema-valid.
 */
@Endpoint
public class InventoryEndpoint {

    private static final String NS = "http://jdoan.com/inventory/v1";

    private final InventoryRepository repo;
    private final DatatypeFactory datatypeFactory;

    public InventoryEndpoint(InventoryRepository repo) throws Exception {
        this.repo = repo;
        this.datatypeFactory = DatatypeFactory.newInstance();
    }

    // ------------------------------------------------------------------
    @PayloadRoot(namespace = NS, localPart = "GetProductRequest")
    @ResponsePayload
    public GetProductResponse getProduct(@RequestPayload GetProductRequest request) {
        ProductType product = repo.findProductBySku(request.getSku())
                .orElseThrow(() -> new NotFoundException(
                        "PRODUCT_NOT_FOUND", "No product with SKU " + request.getSku(), "sku"));

        GetProductResponse response = new GetProductResponse();
        response.setProduct(product);
        return response;
    }

    // ------------------------------------------------------------------
    @PayloadRoot(namespace = NS, localPart = "GetStockLevelRequest")
    @ResponsePayload
    public GetStockLevelResponse getStockLevel(@RequestPayload GetStockLevelRequest request) {
        if (!repo.skuExists(request.getSku())) {
            throw new NotFoundException("PRODUCT_NOT_FOUND",
                    "No product with SKU " + request.getSku(), "sku");
        }
        List<StockLevelType> levels =
                repo.findStockLevels(request.getSku(), request.getWarehouseCode());

        GetStockLevelResponse response = new GetStockLevelResponse();
        response.getStockLevel().addAll(levels);
        return response;
    }

    // ------------------------------------------------------------------
    @PayloadRoot(namespace = NS, localPart = "ListLowStockRequest")
    @ResponsePayload
    public ListLowStockResponse listLowStock(@RequestPayload ListLowStockRequest request) {
        List<LowStockItemType> items =
                repo.findLowStock(request.getWarehouseCode(), request.getMaxResults());

        ListLowStockResponse response = new ListLowStockResponse();
        response.getItem().addAll(items);
        response.setTotalCount(repo.countLowStock(request.getWarehouseCode()));
        return response;
    }

    // ------------------------------------------------------------------
    /**
     * The write path, and the most instructive operation in the service.
     *
     * It reads the quantity, inserts a movement, then reads the quantity again.
     * The two numbers differ because the DATABASE TRIGGER fired in between -
     * this service never issues an UPDATE against stock_levels.
     */
    @PayloadRoot(namespace = NS, localPart = "RecordStockMovementRequest")
    @ResponsePayload
    @Transactional
    public RecordStockMovementResponse recordStockMovement(
            @RequestPayload RecordStockMovementRequest request) {

        String sku = request.getSku();
        String wh  = request.getWarehouseCode();

        if (!repo.skuExists(sku)) {
            throw new NotFoundException("PRODUCT_NOT_FOUND", "No product with SKU " + sku, "sku");
        }
        if (!repo.warehouseExists(wh)) {
            throw new NotFoundException("WAREHOUSE_NOT_FOUND", "No warehouse with code " + wh, "warehouseCode");
        }

        int before = repo.currentQuantity(sku, wh).orElse(0);

        long movementId = repo.insertMovement(
                sku, wh,
                request.getMovementType().value(),   // JAXB enum -> String
                request.getQuantity(),
                request.getReferenceType(),
                request.getNotes());

        int after = repo.currentQuantity(sku, wh).orElse(0);

        // ---- Publish a domain event, transactionally ----------------------
        // This INSERT shares the @Transactional boundary with insertMovement()
        // above, so the movement and its event commit together or not at all.
        //
        // We deliberately do NOT call Kafka from here. A send() inside a
        // transaction is a "dual write": if the transaction later rolls back
        // the event has already escaped, and if the process dies after
        // committing but before sending, the event is lost forever. The relay
        // in phase4-events reads this table and publishes asynchronously.
        repo.insertOutboxEvent(sku, "StockMovementRecorded",
                "inventory.stock-movement.v1",
                """
                {"sku":"%s","warehouseCode":"%s","movementType":"%s","quantity":%d,\
                "quantityBefore":%d,"quantityAfter":%d,"movementId":%d,\
                "referenceType":%s,"occurredAt":"%s"}"""
                        .formatted(sku, wh, request.getMovementType().value(),
                                request.getQuantity(), before, after, movementId,
                                request.getReferenceType() == null
                                        ? "null" : "\"" + request.getReferenceType() + "\"",
                                java.time.Instant.now()));

        RecordStockMovementResponse response = new RecordStockMovementResponse();
        response.setMovementId(movementId);
        response.setSku(sku);
        response.setWarehouseCode(wh);
        response.setQuantityBefore(before);
        response.setQuantityAfter(after);
        response.setRecordedAt(now());
        return response;
    }

    /** xs:dateTime maps to XMLGregorianCalendar in JAXB. */
    private XMLGregorianCalendar now() {
        return datatypeFactory.newXMLGregorianCalendar(
                GregorianCalendar.from(ZonedDateTime.now()));
    }
}
