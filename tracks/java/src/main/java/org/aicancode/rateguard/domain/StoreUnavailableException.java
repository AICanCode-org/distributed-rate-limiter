package org.aicancode.rateguard.domain;

/** Thrown by a store when Redis cannot answer (refused, timed out, broken connection). */
public class StoreUnavailableException extends RuntimeException {

    public StoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public StoreUnavailableException(String message) {
        super(message);
    }
}
