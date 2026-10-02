package org.aicancode.rateguard.store;

import org.aicancode.rateguard.domain.Models.Algorithm;

/**
 * M3: the Redis key layout (spec/architecture.md). One place, so no class builds keys by hand.
 *
 * <p>The {tenant} hash tag makes every limiter key of one tenant hash to the same Redis Cluster slot, so a Lua script
 * that touches the bucket and the usage ring stays legal on a cluster (ADR-0003).
 */
public final class Keys {

    public static final String TENANTS = "rg:tenants";
    /** Minutes kept in the usage ring (3 hours); the API reads at most 120. */
    public static final int USAGE_SLOTS = 180;

    private Keys() {
    }

    public static String tenant(String tenantId) {
        // TODO(phase-3 M3): 'rg:tenant:<id>' (a hash with the tenant's fields).
        throw new UnsupportedOperationException("TODO(phase-3 M3): Keys.tenant");
    }

    public static String apiKey(String keyHash) {
        // TODO(phase-3 M3): 'rg:key:<sha256 of the api key>' -> tenant id.
        throw new UnsupportedOperationException("TODO(phase-3 M3): Keys.apiKey");
    }

    public static String limiter(String tenantId, Algorithm algorithm, String resource) {
        // TODO(phase-3 M3): 'rg:{<id>}:tb:<resource>' for TOKEN_BUCKET, 'rg:{<id>}:sw:<resource>' for
        //   SLIDING_WINDOW. The {id} hash tag keeps a tenant's keys in one cluster slot (ADR-0003).
        throw new UnsupportedOperationException("TODO(phase-3 M3): Keys.limiter");
    }

    public static String usage(String tenantId) {
        // TODO(phase-3 M3): 'rg:{<id>}:usage' (the per-minute usage ring, same hash tag as the limiter keys).
        throw new UnsupportedOperationException("TODO(phase-3 M3): Keys.usage");
    }

    /** SCAN pattern for every limiter key of one tenant (and nobody else's: ids cannot contain '}'). */
    public static String tenantPattern(String tenantId) {
        // TODO(phase-3 M3): SCAN pattern for every key of one tenant: 'rg:{<id>}:*' (ids cannot contain '}',
        //   so no other tenant matches).
        throw new UnsupportedOperationException("TODO(phase-3 M3): Keys.tenantPattern");
    }

    public static int usageSlot(long minute) {
        // TODO(phase-3 M3): minute % USAGE_SLOTS (180): the ring slot of an epoch minute.
        throw new UnsupportedOperationException("TODO(phase-3 M3): Keys.usageSlot");
    }
}
