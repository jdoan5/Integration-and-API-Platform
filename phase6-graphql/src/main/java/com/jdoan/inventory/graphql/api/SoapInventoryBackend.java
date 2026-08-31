package com.jdoan.inventory.graphql.api;

import com.jdoan.inventory.graphql.soapclient.InventorySoapClient;
import com.jdoan.inventory.graphql.soapclient.UpstreamFaultException;
import com.jdoan.inventory.graphql.soapclient.generated.ListLowStockResponse;
import com.jdoan.inventory.graphql.soapclient.generated.ProductType;
import com.jdoan.inventory.graphql.soapclient.generated.RecordStockMovementResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * The real backend: the Phase 1 SOAP service.
 *
 * This is the JAXB-to-GraphQL mapping that used to live inside InventoryService,
 * moved behind {@link InventoryBackend} so the demo profile can swap it out.
 * Nothing about the mapping changed - the DTO seam is still here, and generated
 * SOAP types still stop at this class rather than reaching the schema.
 */
@Component
@Profile("!demo")
public class SoapInventoryBackend implements InventoryBackend {

    private final InventorySoapClient soap;

    public SoapInventoryBackend(InventorySoapClient soap) {
        this.soap = soap;
    }

    @Override
    public Types.Product getProduct(String sku) {
        return translateFaults(() -> {
            ProductType p = soap.getProduct(sku);
            return new Types.Product(p.getSku(), p.getName(), p.getDescription(), p.getCategory(),
                    p.getUnitPrice().doubleValue(), p.getUnitCost().doubleValue(),
                    p.getReorderPoint(), p.getReorderQuantity(), p.isActive());
        });
    }

    @Override
    public List<Types.StockLevel> getStockLevels(String sku, String warehouseCode) {
        return translateFaults(() -> soap.getStockLevels(sku, warehouseCode).stream()
                .map(s -> new Types.StockLevel(
                        s.getSku(), s.getProductName(),
                        WarehouseCodes.toGraphql(s.getWarehouseCode()), s.getWarehouseName(),
                        s.getQuantity(), s.getReorderPoint(),
                        s.getQuantity() < s.getReorderPoint()))
                .toList());
    }

    @Override
    public List<Types.LowStockItem> listLowStock(String warehouseCode, Integer limit) {
        ListLowStockResponse response = soap.listLowStock(warehouseCode, limit);
        return response.getItem().stream()
                .map(i -> new Types.LowStockItem(
                        i.getSku(), i.getProductName(),
                        WarehouseCodes.toGraphql(i.getWarehouseCode()),
                        i.getQuantity(), i.getReorderPoint(),
                        i.getDeficit(), i.getSuggestedOrderQty()))
                .toList();
    }

    @Override
    public Types.MovementResult recordMovement(Types.MovementInput input) {
        String domainWarehouse = WarehouseCodes.toDomain(input.warehouseCode());
        RecordStockMovementResponse response = translateFaults(() ->
                soap.recordMovement(input.sku(), domainWarehouse, input.movementType(),
                        input.quantity(), input.referenceType(), input.notes()));

        return new Types.MovementResult(
                String.valueOf(response.getMovementId()), response.getSku(),
                WarehouseCodes.toGraphql(response.getWarehouseCode()),
                response.getQuantityBefore(), response.getQuantityAfter(),
                response.getQuantityAfter() - response.getQuantityBefore(),
                String.valueOf(response.getRecordedAt()), false);
    }

    /**
     * NOT_FOUND becomes null, everything else propagates.
     *
     * Absence is a valid answer in GraphQL: the field is nullable and null is
     * the response. Throwing would fail sibling fields that resolved perfectly
     * well, which is the difference between a nullable field and an error.
     */
    private <T> T translateFaults(Supplier<T> call) {
        try {
            return call.get();
        } catch (UpstreamFaultException fault) {
            if (fault.getCode().endsWith("NOT_FOUND")) {
                return null;
            }
            throw fault;
        }
    }
}
