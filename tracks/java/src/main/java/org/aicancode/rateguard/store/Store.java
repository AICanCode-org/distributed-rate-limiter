package org.aicancode.rateguard.store;

import java.util.List;
import java.util.Optional;
import org.aicancode.rateguard.domain.Models.Decision;
import org.aicancode.rateguard.domain.Models.Policy;
import org.aicancode.rateguard.domain.Models.Tenant;
import org.aicancode.rateguard.domain.Models.UsagePoint;

/**
 * Storage behind one interface: InMemoryStore (unit tests; v1.1 degraded mode) and RedisStore (M3). A store keeps
 * tenants, answers limiter checks atomically and counts usage per minute. Every method throws
 * StoreUnavailableException when the store cannot answer.
 */
public interface Store {

    boolean ping();

    /** Throws DuplicateException when the id is taken. */
    void createTenant(Tenant tenant);

    Optional<Tenant> getTenant(String tenantId);

    /** Sorted by id. */
    List<Tenant> listTenants();

    Optional<Tenant> findByKeyHash(String keyHash);

    /** Policy, name or key changed. */
    void saveTenant(Tenant tenant, String oldKeyHash);

    void deleteTenant(Tenant tenant);

    /** Forgets the limiter state of one tenant (not its usage history); returns the number of keys removed. */
    int reset(String tenantId);

    Decision check(String tenantId, String resource, Policy policy, int cost);

    /** Oldest first, zeros included. */
    List<UsagePoint> usage(String tenantId, int minutes);
}
