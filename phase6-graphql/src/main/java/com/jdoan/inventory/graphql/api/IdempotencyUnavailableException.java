package com.jdoan.inventory.graphql.api;

/**
 * Raised when a caller supplied an idempotencyKey we cannot honour.
 *
 * A distinct type rather than IllegalStateException so the error resolver can
 * give it a machine-readable code. Spring masks unrecognised exceptions as
 * INTERNAL_ERROR with a correlation id and no message - correct by default,
 * since an exception message is not a public contract, but useless here: this
 * one is entirely actionable and the caller needs to read it.
 */
public class IdempotencyUnavailableException extends RuntimeException {

    public IdempotencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public IdempotencyUnavailableException(String message) {
        super(message);
    }
}
