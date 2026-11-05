package com.iasbank.core.domain.errors;

/**
 * Raised when an already processed request reference arrives with different data.
 * The original transfer is preserved untouched.
 */
public class RequestReferenceConflictException extends RuntimeException {

    public RequestReferenceConflictException(String requestReference) {
        super("Request reference " + requestReference + " was already used with different data");
    }
}
