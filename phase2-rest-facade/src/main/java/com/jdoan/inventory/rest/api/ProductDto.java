package com.jdoan.inventory.rest.api;

import java.math.BigDecimal;

/**
 * The REST representation of a product.
 *
 * Deliberately NOT the JAXB-generated ProductType. Exposing generated SOAP
 * classes through a REST API couples your public JSON shape to the provider's
 * XSD - change the schema and every consumer's JSON changes with it. A
 * separate DTO is the seam that lets the two evolve independently.
 */
public record ProductDto(
        String sku,
        String name,
        String description,
        String category,
        BigDecimal unitPrice,
        BigDecimal unitCost,
        int reorderPoint,
        int reorderQuantity,
        boolean active
) {}
