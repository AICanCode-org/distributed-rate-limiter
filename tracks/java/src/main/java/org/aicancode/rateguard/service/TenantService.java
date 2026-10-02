package org.aicancode.rateguard.service;

import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;
import org.aicancode.rateguard.domain.Models.Tenant;
import org.aicancode.rateguard.domain.Models.UsagePoint;
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

    public TenantService(Store store, Clock clock, Supplier<String> newKey) {
        this.store = store;
        this.clock = clock;
        this.newKey = newKey;
    }

    public Store store() {
        return store;
    }

    public Created create(JsonNode payload) {
        // TODO(phase-3 M4): validate id, name and policy; new API key; store only its hash and prefix (the
        //   key is returned ONCE); createdAt = updatedAt = the injected clock. DuplicateError -> 409 tenant-
        //   exists.
        throw new UnsupportedOperationException("TODO(phase-3 M4): TenantService.create");
    }

    public List<Tenant> list() {
        // TODO(phase-3 M4): all tenants, sorted by id.
        throw new UnsupportedOperationException("TODO(phase-3 M4): TenantService.list");
    }

    public Tenant get(String tenantId) {
        // TODO(phase-3 M4): an id that is not a valid tenant id is simply 404 not-found 'tenant' (never 400,
        //   never a Redis call); unknown -> 404.
        throw new UnsupportedOperationException("TODO(phase-3 M4): TenantService.get");
    }

    public Tenant setPolicy(String tenantId, JsonNode payload) {
        // TODO(phase-3 M4): get, validate the new policy, bump updatedAt, save (the key hash does not
        //   change).
        throw new UnsupportedOperationException("TODO(phase-3 M4): TenantService.setPolicy");
    }

    /** A new key; the old one stops working at once. */
    public Created rotateKey(String tenantId) {
        // TODO(phase-3 M4): get, new key, save with the new hash/prefix (the old key stops working at once),
        //   return it ONCE.
        throw new UnsupportedOperationException("TODO(phase-3 M4): TenantService.rotateKey");
    }

    public void delete(String tenantId) {
        // TODO(phase-3 M4): get (404 rules) then delete_tenant.
        throw new UnsupportedOperationException("TODO(phase-3 M4): TenantService.delete");
    }

    public int reset(String tenantId) {
        // TODO(phase-3 M4): get (404 rules) then reset the limiter keys.
        throw new UnsupportedOperationException("TODO(phase-3 M4): TenantService.reset");
    }

    public Usage usage(String tenantId, String minutesParam) {
        // TODO(phase-3 M4): validate minutes, get (404 rules), read the usage points.
        throw new UnsupportedOperationException("TODO(phase-3 M4): TenantService.usage");
    }

    /** X-API-Key -> tenant. Malformed keys are rejected without touching Redis. */
    public Tenant authenticate(String apiKey) {
        // TODO(phase-3 M4): malformed key -> 401 invalid-api-key WITHOUT touching Redis; otherwise find the
        //   tenant by the key's hash; unknown -> 401 invalid-api-key.
        throw new UnsupportedOperationException("TODO(phase-3 M4): TenantService.authenticate");
    }
}
