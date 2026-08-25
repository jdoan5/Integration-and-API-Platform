package com.jdoan.inventory.rest.api;

import jakarta.validation.constraints.*;

/**
 * Bean Validation mirrors the XSD constraints from Phase 1.
 *
 * Why duplicate them? So invalid input fails FAST with a clean HTTP 400 here,
 * instead of travelling to the SOAP service and coming back as a SOAP fault
 * that has to be translated. The SOAP service still validates independently -
 * never trust a caller, even your own facade.
 */
public record MovementRequestDto(
        @NotBlank
        @Pattern(regexp = "[A-Z]{3,4}-[A-Z0-9]{3,5}-?[0-9]{0,5}", message = "invalid SKU format")
        String sku,

        @NotBlank
        @Pattern(regexp = "WH-[A-Z]{2,4}", message = "invalid warehouse code")
        String warehouseCode,

        @NotBlank
        @Pattern(regexp = "IN|OUT|TRANSFER_IN|TRANSFER_OUT|ADJUSTMENT|RETURN",
                 message = "movementType must be one of IN, OUT, TRANSFER_IN, TRANSFER_OUT, ADJUSTMENT, RETURN")
        String movementType,

        @Positive(message = "quantity must be positive")
        @Max(value = 1_000_000, message = "quantity exceeds maximum")
        int quantity,

        String referenceType,
        String notes
) {}
