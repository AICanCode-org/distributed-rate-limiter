package org.aicancode.rateguard.store;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.aicancode.rateguard.domain.DuplicateException;
import org.aicancode.rateguard.domain.Models.Algorithm;
import org.aicancode.rateguard.domain.Models.Decision;
import org.aicancode.rateguard.domain.Models.Policy;
import org.aicancode.rateguard.domain.Models.Tenant;
import org.aicancode.rateguard.domain.Models.UsagePoint;
import org.aicancode.rateguard.domain.StoreUnavailableException;
import org.aicancode.rateguard.limiter.Algorithms;
import org.aicancode.rateguard.limiter.Algorithms.BucketState;
import org.aicancode.rateguard.limiter.Algorithms.WindowState;

/**
 * Everything in maps, using the injected clock. Every method is synchronized, which makes each check atomic, just
 * like one Lua script call in Redis. Read it: it shows exactly what each RedisStore method must do.
 */
public class InMemoryStore implements Store {

    private final Clock clock;
    private final Map<String, Tenant> tenants = new TreeMap<>();
    /** Key hash -> tenant id. */
    private final Map<String, String> keys = new HashMap<>();
    private final Map<String, Object> limiters = new HashMap<>();
    /** Tenant -> minute -> {allowed, denied}. */
    private final Map<String, Map<Long, long[]>> usage = new HashMap<>();
    /** Tests flip this to simulate an outage. */
    public volatile boolean up = true;

    public InMemoryStore(Clock clock) {
        this.clock = clock;
    }

    private void guard() {
        if (!up) {
            throw new StoreUnavailableException("in-memory store is down (test)");
        }
    }

    @Override
    public boolean ping() {
        return up;
    }

    @Override
    public synchronized void createTenant(Tenant tenant) {
        guard();
        if (tenants.containsKey(tenant.id())) {
            throw new DuplicateException(tenant.id());
        }
        tenants.put(tenant.id(), tenant);
        keys.put(tenant.keyHash(), tenant.id());
    }

    @Override
    public synchronized Optional<Tenant> getTenant(String tenantId) {
        guard();
        return Optional.ofNullable(tenants.get(tenantId));
    }

    @Override
    public synchronized List<Tenant> listTenants() {
        guard();
        return new ArrayList<>(tenants.values());
    }

    @Override
    public synchronized Optional<Tenant> findByKeyHash(String keyHash) {
        guard();
        String id = keys.get(keyHash);
        return id == null ? Optional.empty() : Optional.ofNullable(tenants.get(id));
    }

    @Override
    public synchronized void saveTenant(Tenant tenant, String oldKeyHash) {
        guard();
        if (!oldKeyHash.equals(tenant.keyHash())) {
            keys.remove(oldKeyHash);
            keys.put(tenant.keyHash(), tenant.id());
        }
        tenants.put(tenant.id(), tenant);
    }

    @Override
    public synchronized void deleteTenant(Tenant tenant) {
        guard();
        tenants.remove(tenant.id());
        keys.remove(tenant.keyHash());
        usage.remove(tenant.id());
        resetNow(tenant.id());
    }

    @Override
    public synchronized int reset(String tenantId) {
        guard();
        return resetNow(tenantId);
    }

    private int resetNow(String tenantId) {
        String pattern = Keys.tenantPattern(tenantId);
        String prefix = pattern.substring(0, pattern.length() - 1);
        List<String> doomed = limiters.keySet().stream().filter(k -> k.startsWith(prefix)).toList();
        doomed.forEach(limiters::remove);
        return doomed.size();
    }

    @Override
    public synchronized Decision check(String tenantId, String resource, Policy policy, int cost) {
        guard();
        long now = clock.millis();
        String key = Keys.limiter(tenantId, policy.algorithm(), resource);
        Decision decision;
        if (policy.algorithm() == Algorithm.TOKEN_BUCKET) {
            var r = Algorithms.tokenBucket((BucketState) limiters.get(key), policy.limit(), policy.windowMs(), cost, now);
            limiters.put(key, r.state());
            decision = r.decision();
        } else {
            var r = Algorithms.slidingWindow((WindowState) limiters.get(key), policy.limit(), policy.windowMs(), cost, now);
            limiters.put(key, r.state());
            decision = r.decision();
        }
        long[] counts = usage.computeIfAbsent(tenantId, t -> new HashMap<>())
                .computeIfAbsent(Math.floorDiv(now, 60_000L), m -> new long[2]);
        counts[decision.allowed() ? 0 : 1]++;
        return decision;
    }

    @Override
    public synchronized List<UsagePoint> usage(String tenantId, int minutes) {
        guard();
        long current = Math.floorDiv(clock.millis(), 60_000L);
        Map<Long, long[]> counts = usage.getOrDefault(tenantId, Map.of());
        List<UsagePoint> out = new ArrayList<>();
        for (long minute = current - minutes + 1; minute <= current; minute++) {
            long[] c = counts.getOrDefault(minute, new long[2]);
            out.add(new UsagePoint(minute, c[0], c[1]));
        }
        return out;
    }
}
