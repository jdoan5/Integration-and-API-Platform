package com.jdoan.inventory.soap.error;

/**
 * A business-level "you asked for something that isn't there" error.
 *
 * It carries a machine-readable {@code code} and the offending {@code field}
 * so the fault resolver can build a STRUCTURED SOAP fault detail - the
 * InventoryFault element defined in the XSD - rather than just a prose string.
 *
 * Why this matters for service contracts: consumers should be able to branch
 * on an error programmatically. If the only signal is faultstring text, every
 * consumer ends up string-matching your English, and you can never reword a
 * message without breaking someone.
 */
public class NotFoundException extends RuntimeException {

    private final String code;
    private final String field;

    public NotFoundException(String code, String message, String field) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public String getCode()  { return code; }
    public String getField() { return field; }
}
