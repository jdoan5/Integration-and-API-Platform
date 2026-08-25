package com.jdoan.inventory.rest.soapclient;

/**
 * A SOAP fault from the upstream service, already parsed into its
 * machine-readable parts.
 *
 * The point is that nothing above this class ever touches SOAP types. The
 * REST layer sees a {@code code} like PRODUCT_NOT_FOUND and maps it to an
 * HTTP status - no XML, no string-matching on English prose.
 */
public class UpstreamFaultException extends RuntimeException {

    private final String code;
    private final String field;

    public UpstreamFaultException(String code, String field, String message) {
        super(message);
        this.code = code == null ? "UNKNOWN" : code;
        this.field = field;
    }

    public String getCode()  { return code; }
    public String getField() { return field; }
}
