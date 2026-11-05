package com.iasbank.core.domain.errors;

/**
 * Raised when a transfer with the same request reference was registered concurrently.
 */
public class DuplicateTransferException extends RuntimeException {

    public DuplicateTransferException(String requestReference) {
        super("Transfer already registered: " + requestReference);
    }
}
