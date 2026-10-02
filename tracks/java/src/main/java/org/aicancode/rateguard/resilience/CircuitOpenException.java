package org.aicancode.rateguard.resilience;

/** The breaker is open: the call was skipped. */
public class CircuitOpenException extends RuntimeException {

    public CircuitOpenException(String message) {
        super(message);
    }
}
