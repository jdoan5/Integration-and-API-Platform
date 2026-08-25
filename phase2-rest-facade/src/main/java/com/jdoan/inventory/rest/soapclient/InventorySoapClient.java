package com.jdoan.inventory.rest.soapclient;

import com.jdoan.inventory.rest.soapclient.generated.*;
import org.springframework.stereotype.Component;
import org.springframework.ws.client.core.WebServiceTemplate;

import java.util.List;

/**
 * Thin wrapper over the SOAP service.
 *
 * Everything SOAP-shaped stops here. The REST layer above never sees a
 * JAXB type or a SoapFaultClientException - that isolation is what makes it
 * possible to eventually replace the SOAP backend without touching the API.
 */
@Component
public class InventorySoapClient {

    private final WebServiceTemplate ws;

    public InventorySoapClient(WebServiceTemplate ws) {
        this.ws = ws;
    }

    public ProductType getProduct(String sku) {
        GetProductRequest request = new GetProductRequest();
        request.setSku(sku);
        GetProductResponse response = (GetProductResponse) ws.marshalSendAndReceive(request, CorrelationIdPropagator.propagate());
        return response.getProduct();
    }

    public List<StockLevelType> getStockLevels(String sku, String warehouseCode) {
        GetStockLevelRequest request = new GetStockLevelRequest();
        request.setSku(sku);
        request.setWarehouseCode(warehouseCode);   // null = all warehouses
        GetStockLevelResponse response = (GetStockLevelResponse) ws.marshalSendAndReceive(request, CorrelationIdPropagator.propagate());
        return response.getStockLevel();
    }

    public ListLowStockResponse listLowStock(String warehouseCode, Integer maxResults) {
        ListLowStockRequest request = new ListLowStockRequest();
        request.setWarehouseCode(warehouseCode);
        request.setMaxResults(maxResults);
        return (ListLowStockResponse) ws.marshalSendAndReceive(request, CorrelationIdPropagator.propagate());
    }

    public RecordStockMovementResponse recordMovement(String sku, String warehouseCode,
                                                      String movementType, int quantity,
                                                      String referenceType, String notes) {
        RecordStockMovementRequest request = new RecordStockMovementRequest();
        request.setSku(sku);
        request.setWarehouseCode(warehouseCode);
        request.setMovementType(MovementTypeType.fromValue(movementType));
        request.setQuantity(quantity);
        request.setReferenceType(referenceType);
        request.setNotes(notes);
        return (RecordStockMovementResponse) ws.marshalSendAndReceive(request, CorrelationIdPropagator.propagate());
    }

}
