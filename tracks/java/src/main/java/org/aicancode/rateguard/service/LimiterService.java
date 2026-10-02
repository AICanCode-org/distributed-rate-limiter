package org.aicancode.rateguard.service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aicancode.rateguard.domain.ApiException;
import org.aicancode.rateguard.domain.Models.Decision;
import org.aicancode.rateguard.domain.Models.FailMode;
import org.aicancode.rateguard.domain.Models.Tenant;
import org.aicancode.rateguard.domain.Models.Verdict;
import org.aicancode.rateguard.domain.StoreUnavailableException;
import org.aicancode.rateguard.domain.Validation;
import org.aicancode.rateguard.metrics.Metrics;
import org.aicancode.rateguard.store.InMemoryStore;
import org.aicancode.rateguard.store.Store;
import tools.jackson.databind.JsonNode;

/** M4: the limiter service: validate a check, ask the store, turn the decision into HTTP headers. */
public class LimiterService {

    public static final String DEMO_RESOURCE = "demo";

    private final Store store;
    /** v1.1: the per-instance limiter used by failMode LOCAL while Redis is unavailable. */
    private final InMemoryStore local;
    private final Metrics metrics;

    public LimiterService(Store store, InMemoryStore local, Metrics metrics) {
        this.store = store;
        this.local = local;
        this.metrics = metrics;
    }

    public LimiterService(Store store) {
        this(store, new InMemoryStore(Clock.systemUTC()), new Metrics());
    }

    public static long secondsUp(long ms) {
        return Math.ceilDiv(ms, 1000L);
    }

    /** POST /limits/check: the decision API other services call before doing the work. */
    public Verdict check(Tenant tenant, JsonNode payload) {
        boolean empty = payload == null || payload.isNull() || payload.isMissingNode();
        JsonNode body = empty ? null : Validation.requireObject(payload);
        return decide(tenant, Validation.resource(body == null ? null : body.get("resource")),
                Validation.cost(body == null ? null : body.get("cost")));
    }

    public Verdict decide(Tenant tenant, String resource, int cost) {
        if (cost > tenant.policy().limit()) {
            throw ApiException.costExceedsLimit(cost, tenant.policy().limit());
        }
        Decision decision;
        try {
            decision = store.check(tenant.id(), resource, tenant.policy(), cost);
        } catch (StoreUnavailableException e) {
            decision = degraded(tenant, resource, cost);
        }
        metrics.inc(decision.allowed() ? Metrics.DECISIONS_ALLOWED : Metrics.DECISIONS_DENIED);
        return new Verdict(tenant, resource, cost, decision);
    }

    /** v1.1: Redis is unavailable, so the tenant's failMode decides (ADR-0006). */
    private Decision degraded(Tenant tenant, String resource, int cost) {
        FailMode mode = tenant.policy().failMode();
        if (mode == FailMode.CLOSED) {
            // without its store the limiter cannot promise anything (ADR-0004)
            throw ApiException.limiterUnavailable();
        }
        metrics.inc(Metrics.DEGRADED_DECISIONS);
        if (mode == FailMode.OPEN) {
            return new Decision(true, tenant.policy().limit() - cost, 0, 0, true);
        }
        // LOCAL: an exact limit per instance (N instances may allow up to N x limit in total)
        return local.check(tenant.id(), resource, tenant.policy(), cost).asDegraded();
    }

    /** Middleware style: a denied request becomes a 429 problem with Retry-After. */
    public Verdict enforce(Tenant tenant, String resource) {
        Verdict verdict = decide(tenant, resource, 1);
        if (!verdict.decision().allowed()) {
            throw tooMany(verdict);
        }
        return verdict;
    }

    public Verdict enforce(Tenant tenant) {
        return enforce(tenant, DEMO_RESOURCE);
    }

    public static ApiException tooMany(Verdict verdict) {
        long retryS = Math.max(1, secondsUp(verdict.decision().retryAfterMs()));
        Map<String, String> headers = new LinkedHashMap<>(headers(verdict));
        headers.put("Retry-After", Long.toString(retryS));
        return ApiException.rateLimited(verdict.tenant().policy().limit(), verdict.tenant().policy().windowSeconds(),
                retryS, headers);
    }

    public static Map<String, String> headers(Verdict verdict) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-RateLimit-Limit", Integer.toString(verdict.tenant().policy().limit()));
        h.put("X-RateLimit-Remaining", Long.toString(verdict.decision().remaining()));
        h.put("X-RateLimit-Reset", Long.toString(secondsUp(verdict.decision().resetAfterMs())));
        if (verdict.decision().degraded()) {
            h.put("X-RateLimit-Degraded", "true");
        }
        return h;
    }
}
