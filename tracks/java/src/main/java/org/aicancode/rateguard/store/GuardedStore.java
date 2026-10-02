package org.aicancode.rateguard.store;

import java.util.List;
import java.util.Optional;
import org.aicancode.rateguard.domain.Models.Decision;
import org.aicancode.rateguard.domain.Models.Policy;
import org.aicancode.rateguard.domain.Models.Tenant;
import org.aicancode.rateguard.domain.Models.UsagePoint;
import org.aicancode.rateguard.domain.StoreUnavailableException;
import org.aicancode.rateguard.resilience.CircuitBreaker;
import org.aicancode.rateguard.resilience.CircuitOpenException;

/**
 * v1.1: any store behind a circuit breaker (ADR-0006).
 *
 * <p>While Redis keeps failing, every call would wait for REDIS_TIMEOUT_MS before failing. After {@code threshold}
 * failures in a row the breaker opens and calls fail at once with StoreUnavailableException, so the limiter can
 * answer from the tenant's failMode in microseconds instead of seconds. DuplicateException is an answer from Redis,
 * not a failure (the breaker is built with {@code isFailure = e -> e instanceof StoreUnavailableException}).
 */
public class GuardedStore implements Store {

    private final Store inner;
    private final CircuitBreaker breaker;
    private final Runnable onError;

    public GuardedStore(Store inner, CircuitBreaker breaker, Runnable onError) {
        this.inner = inner;
        this.breaker = breaker;
        this.onError = onError;
    }

    public CircuitBreaker breaker() {
        return breaker;
    }

    private <T> T run(CircuitBreaker.Call<T> fn) {
        try {
            return breaker.call(fn);
        } catch (CircuitOpenException e) {
            throw new StoreUnavailableException(e.getMessage());
        } catch (StoreUnavailableException e) {
            onError.run();
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e); // a Store never throws checked exceptions
        }
    }

    @Override
    public boolean ping() {
        try {
            return run(() -> {
                if (!inner.ping()) {
                    throw new StoreUnavailableException("ping failed");
                }
                return true;
            });
        } catch (StoreUnavailableException e) {
            return false;
        }
    }

    @Override
    public void createTenant(Tenant tenant) {
        run(() -> {
            inner.createTenant(tenant);
            return null;
        });
    }

    @Override
    public Optional<Tenant> getTenant(String tenantId) {
        return run(() -> inner.getTenant(tenantId));
    }

    @Override
    public List<Tenant> listTenants() {
        return run(inner::listTenants);
    }

    @Override
    public Optional<Tenant> findByKeyHash(String keyHash) {
        return run(() -> inner.findByKeyHash(keyHash));
    }

    @Override
    public void saveTenant(Tenant tenant, String oldKeyHash) {
        run(() -> {
            inner.saveTenant(tenant, oldKeyHash);
            return null;
        });
    }

    @Override
    public void deleteTenant(Tenant tenant) {
        run(() -> {
            inner.deleteTenant(tenant);
            return null;
        });
    }

    @Override
    public int reset(String tenantId) {
        return run(() -> inner.reset(tenantId));
    }

    @Override
    public Decision check(String tenantId, String resource, Policy policy, int cost) {
        return run(() -> inner.check(tenantId, resource, policy, cost));
    }

    @Override
    public List<UsagePoint> usage(String tenantId, int minutes) {
        return run(() -> inner.usage(tenantId, minutes));
    }
}
