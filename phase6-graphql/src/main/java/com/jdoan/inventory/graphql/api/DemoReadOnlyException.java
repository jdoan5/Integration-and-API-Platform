package com.jdoan.inventory.graphql.api;

/**
 * Raised when the read-only demo profile is asked to write.
 *
 * A distinct type so the error resolver can attach a machine-readable code
 * rather than letting Spring mask it as INTERNAL_ERROR. A visitor who tries the
 * mutation should get an explanation, not a correlation id.
 */
public class DemoReadOnlyException extends RuntimeException {
    public DemoReadOnlyException(String message) {
        super(message);
    }
}
