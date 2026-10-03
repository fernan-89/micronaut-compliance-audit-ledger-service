package com.thinklab.domain.exception;

/**
 * Domain Exception: another writer took the next position of the tenant's chain first, and the append
 * could not be completed after its bounded retries (ADR-030).
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict. The request itself is fine; retrying it is the right response.
 */
public class ChainConflictException extends BusinessException {

    private static final String ERROR_CODE = "ERR-LED-00409";

    public ChainConflictException(String message) {
        super(ERROR_CODE, message);
    }
}
