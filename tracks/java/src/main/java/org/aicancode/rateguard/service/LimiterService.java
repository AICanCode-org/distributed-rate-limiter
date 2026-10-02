package org.aicancode.rateguard.service;

import java.util.Map;
import org.aicancode.rateguard.domain.ApiException;
import org.aicancode.rateguard.domain.Models.Tenant;
import org.aicancode.rateguard.domain.Models.Verdict;
import org.aicancode.rateguard.store.Store;
import tools.jackson.databind.JsonNode;

/** M4: the limiter service: validate a check, ask the store, turn the decision into HTTP headers. */
public class LimiterService {

    public static final String DEMO_RESOURCE = "demo";

    private final Store store;

    public LimiterService(Store store) {
        this.store = store;
    }

    public static long secondsUp(long ms) {
        // TODO(phase-3 M4): ms -> whole seconds, rounded UP (1 ms -> 1 s, 0 -> 0).
        throw new UnsupportedOperationException("TODO(phase-3 M4): LimiterService.secondsUp");
    }

    /** POST /limits/check: the decision API other services call before doing the work. */
    public Verdict check(Tenant tenant, JsonNode payload) {
        // TODO(phase-3 M4): POST /limits/check body: missing/null -> {}; otherwise an object with optional
        //   resource and cost (validation); then decide().
        throw new UnsupportedOperationException("TODO(phase-3 M4): LimiterService.check");
    }

    public Verdict decide(Tenant tenant, String resource, int cost) {
        // TODO(phase-3 M4): cost above the tenant's limit -> 422 cost-exceeds-limit (it could never be
        //   allowed); ask the store; StoreUnavailable -> 503 limiter-unavailable (fail CLOSED, ADR-0004).
        throw new UnsupportedOperationException("TODO(phase-3 M4): LimiterService.decide");
    }

    /** Middleware style: a denied request becomes a 429 problem with Retry-After. */
    public Verdict enforce(Tenant tenant, String resource) {
        // TODO(phase-3 M4): decide(tenant, resource, cost 1); denied -> raise too_many(verdict).
        throw new UnsupportedOperationException("TODO(phase-3 M4): LimiterService.enforce");
    }

    public Verdict enforce(Tenant tenant) {
        // TODO(phase-3 M4): decide(tenant, resource, cost 1); denied -> raise too_many(verdict).
        throw new UnsupportedOperationException("TODO(phase-3 M4): LimiterService.enforce");
    }

    public static ApiException tooMany(Verdict verdict) {
        // TODO(phase-3 M4): 429 rate-limited with the X-RateLimit-* headers and Retry-After = max(1,
        //   seconds_up(retryAfterMs)).
        throw new UnsupportedOperationException("TODO(phase-3 M4): LimiterService.tooMany");
    }

    public static Map<String, String> headers(Verdict verdict) {
        // TODO(phase-3 M4): X-RateLimit-Limit (the policy limit), X-RateLimit-Remaining, X-RateLimit-Reset
        //   (secondsUp(resetAfterMs)).
        throw new UnsupportedOperationException("TODO(phase-3 M4): LimiterService.headers");
    }
}
