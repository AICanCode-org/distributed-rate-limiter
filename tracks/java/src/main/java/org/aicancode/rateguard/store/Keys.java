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
        return "rg:tenant:" + tenantId;
    }

    public static String apiKey(String keyHash) {
        return "rg:key:" + keyHash;
    }

    public static String limiter(String tenantId, Algorithm algorithm, String resource) {
        return "rg:{" + tenantId + "}:" + (algorithm == Algorithm.TOKEN_BUCKET ? "tb" : "sw") + ":" + resource;
    }

    public static String usage(String tenantId) {
        return "rg:{" + tenantId + "}:usage";
    }

    /** SCAN pattern for every limiter key of one tenant (and nobody else's: ids cannot contain '}'). */
    public static String tenantPattern(String tenantId) {
        return "rg:{" + tenantId + "}:*";
    }

    public static int usageSlot(long minute) {
        return (int) Math.floorMod(minute, (long) USAGE_SLOTS);
    }
}
