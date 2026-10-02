package org.aicancode.rateguard.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.aicancode.rateguard.domain.ApiException;
import org.aicancode.rateguard.domain.DuplicateException;
import org.aicancode.rateguard.domain.Models.Policy;
import org.aicancode.rateguard.domain.Models.Tenant;
import org.aicancode.rateguard.domain.Models.UsagePoint;
import org.aicancode.rateguard.domain.StoreUnavailableException;
import org.aicancode.rateguard.domain.Times;
import org.aicancode.rateguard.domain.Validation;
import org.aicancode.rateguard.security.ApiKeys;
import org.aicancode.rateguard.store.Store;
import tools.jackson.databind.JsonNode;

/** M4: tenants (the admin API's business logic) and API-key authentication. */
public class TenantService {

    /** A new tenant (or a rotated key) and its API key. The key is shown ONCE; only its hash is stored. */
    public record Created(Tenant tenant, String apiKey) {
    }

    public record Usage(Tenant tenant, int minutes, List<UsagePoint> points) {
    }

    private final Store store;
    private final Clock clock;
    private final Supplier<String> newKey;
    private final Runnable onCreated;
    /**
     * v1.1: the last tenant Redis returned for each key hash, used ONLY while Redis is unavailable, so a failMode can
     * still be applied to a known tenant (ADR-0006).
     */
    private final Map<String, Tenant> lastKnown = new ConcurrentHashMap<>();

    public TenantService(Store store, Clock clock, Supplier<String> newKey, Runnable onCreated) {
        this.store = store;
        this.clock = clock;
        this.newKey = newKey;
        this.onCreated = onCreated;
    }

    public TenantService(Store store, Clock clock, Supplier<String> newKey) {
        this(store, clock, newKey, () -> { });
    }

    public Store store() {
        return store;
    }

    public Created create(JsonNode payload) {
        JsonNode body = Validation.requireObject(payload);
        String id = Validation.tenantId(body.get("id"));
        String name = Validation.tenantName(body.get("name"));
        Policy policy = Validation.policy(body.get("policy"));
        String apiKey = newKey.get();
        Instant now = Times.now(clock);
        Tenant tenant = new Tenant(id, name, policy, ApiKeys.hash(apiKey), ApiKeys.prefix(apiKey), now, now);
        try {
            store.createTenant(tenant);
        } catch (DuplicateException e) {
            throw ApiException.tenantExists();
        }
        onCreated.run();
        return new Created(tenant, apiKey);
    }

    public List<Tenant> list() {
        return store.listTenants();
    }

    public Tenant get(String tenantId) {
        // An id that cannot exist is simply not found (never a 400, never a Redis call).
        if (!Validation.isTenantId(tenantId)) {
            throw ApiException.notFound("tenant");
        }
        return store.getTenant(tenantId).orElseThrow(() -> ApiException.notFound("tenant"));
    }

    public Tenant setPolicy(String tenantId, JsonNode payload) {
        Tenant t = get(tenantId);
        Tenant updated = new Tenant(t.id(), t.name(), Validation.policy(payload), t.keyHash(), t.keyPrefix(),
                t.createdAt(), Times.now(clock));
        store.saveTenant(updated, t.keyHash());
        return updated;
    }

    /** A new key; the old one stops working at once. */
    public Created rotateKey(String tenantId) {
        Tenant t = get(tenantId);
        String apiKey = newKey.get();
        Tenant updated = new Tenant(t.id(), t.name(), t.policy(), ApiKeys.hash(apiKey), ApiKeys.prefix(apiKey),
                t.createdAt(), Times.now(clock));
        store.saveTenant(updated, t.keyHash());
        return new Created(updated, apiKey);
    }

    public void delete(String tenantId) {
        store.deleteTenant(get(tenantId));
    }

    public int reset(String tenantId) {
        return store.reset(get(tenantId).id());
    }

    public Usage usage(String tenantId, String minutesParam) {
        int minutes = Validation.minutes(minutesParam);
        Tenant tenant = get(tenantId);
        return new Usage(tenant, minutes, store.usage(tenant.id(), minutes));
    }

    /** X-API-Key -> tenant. Malformed keys are rejected without touching Redis. */
    public Tenant authenticate(String apiKey) {
        if (!ApiKeys.looksLikeApiKey(apiKey)) {
            throw ApiException.invalidApiKey();
        }
        String keyHash = ApiKeys.hash(apiKey);
        Optional<Tenant> found;
        try {
            found = store.findByKeyHash(keyHash);
        } catch (StoreUnavailableException e) {
            Tenant cached = lastKnown.get(keyHash);
            if (cached == null) {
                throw e; // an unknown key during an outage: nothing to decide with -> 503
            }
            return cached;
        }
        if (found.isEmpty()) {
            lastKnown.remove(keyHash);
            throw ApiException.invalidApiKey();
        }
        lastKnown.put(keyHash, found.get());
        return found.get();
    }
}
