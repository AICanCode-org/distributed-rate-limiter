package org.aicancode.rateguard.resilience;

import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * v1.1 (after the game day): a circuit breaker, used in front of Redis and in front of the LLM.
 *
 * <p>CLOSED: calls go through; {@code threshold} failures in a row open the circuit. OPEN: calls fail immediately
 * with CircuitOpenException (no timeout to wait for) for {@code cooldownMs}. HALF-OPEN: after the cooldown ONE trial
 * call goes through; success closes the circuit, failure re-opens it.
 */
public class CircuitBreaker {

    private static final Logger LOG = LoggerFactory.getLogger(CircuitBreaker.class);

    /** Like a Supplier, but may throw checked exceptions (the LLM client does). */
    @FunctionalInterface
    public interface Call<T> {
        T run() throws Exception;
    }

    private final String name;
    private final int threshold;
    private final long cooldownMs;
    private final LongSupplier nowMs;
    private final Predicate<Exception> isFailure;
    private final Runnable onShortCircuit;

    private int failures;
    private Long openedAt;
    private boolean trialRunning;

    public CircuitBreaker(String name, int threshold, long cooldownMs, LongSupplier nowMs,
            Predicate<Exception> isFailure, Runnable onShortCircuit) {
        this.name = name;
        this.threshold = threshold;
        this.cooldownMs = cooldownMs;
        this.nowMs = nowMs;
        this.isFailure = isFailure;
        this.onShortCircuit = onShortCircuit;
    }

    public synchronized String state() {
        if (openedAt == null) {
            return "closed";
        }
        return nowMs.getAsLong() - openedAt >= cooldownMs ? "half-open" : "open";
    }

    public <T> T call(Call<T> fn) throws Exception {
        synchronized (this) {
            String state = state();
            if ("open".equals(state) || ("half-open".equals(state) && trialRunning)) {
                onShortCircuit.run();
                throw new CircuitOpenException(name + " circuit open: skipping the call");
            }
            trialRunning = "half-open".equals(state);
        }
        T result;
        try {
            result = fn.run();
        } catch (Exception e) {
            synchronized (this) {
                trialRunning = false;
                if (isFailure.test(e)) {
                    failures++;
                    if (openedAt != null || failures >= threshold) {
                        openedAt = nowMs.getAsLong();
                        LOG.warn("{} circuit opened after {} failure(s)", name, failures);
                    }
                } else {
                    close(); // the dependency answered (e.g. "duplicate id"): it is healthy
                }
            }
            throw e;
        }
        synchronized (this) {
            trialRunning = false;
            close();
        }
        return result;
    }

    private void close() {
        if (openedAt != null) {
            LOG.info("{} circuit closed again", name);
        }
        failures = 0;
        openedAt = null;
    }
}
