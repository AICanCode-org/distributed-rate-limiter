// M4: the limiter service: validate a check, ask the store, turn the decision into HTTP headers.
import { ApiError, costExceedsLimit, limiterUnavailable, rateLimited, StoreUnavailable } from "./errors.js";
import { Metrics } from "./metrics.js";
import { CLOSED, OPEN, type Decision, type Tenant, type Verdict } from "./models.js";
import { InMemoryStore, type Store } from "./store.js";
import * as v from "./validation.js";

export const DEMO_RESOURCE = "demo";

export const secondsUp = (ms: number): number => Math.ceil(ms / 1000);

export class LimiterService {
  constructor(
    readonly store: Store,
    /** v1.1: the per-instance limiter used by failMode LOCAL while Redis is unavailable */
    readonly local: InMemoryStore = new InMemoryStore(),
    readonly metrics: Metrics = new Metrics(),
  ) {}

  /** POST /limits/check: the decision API other services call before doing the work. */
  async check(tenant: Tenant, payload: unknown): Promise<Verdict> {
    const body = payload === undefined || payload === null ? {} : v.requireObject(payload);
    return this.decide(tenant, v.resource(body.resource), v.cost(body.cost));
  }

  async decide(tenant: Tenant, resource: string, cost: number): Promise<Verdict> {
    if (cost > tenant.policy.limit) throw costExceedsLimit(cost, tenant.policy.limit);
    let decision: Decision;
    try {
      decision = await this.store.check(tenant.id, resource, tenant.policy, cost);
    } catch (err) {
      if (!(err instanceof StoreUnavailable)) throw err;
      decision = await this.degraded(tenant, resource, cost);
    }
    this.metrics.inc(
      decision.allowed ? "rateguard_decisions_allowed_total" : "rateguard_decisions_denied_total",
    );
    return { tenant, resource, cost, decision };
  }

  /** v1.1: Redis is unavailable, so the tenant's failMode decides (ADR-0006). */
  private async degraded(tenant: Tenant, resource: string, cost: number): Promise<Decision> {
    const mode = tenant.policy.failMode;
    // without its store the limiter cannot promise anything (ADR-0004)
    if (mode === CLOSED) throw limiterUnavailable();
    this.metrics.inc("rateguard_degraded_decisions_total");
    if (mode === OPEN) {
      return {
        allowed: true,
        remaining: tenant.policy.limit - cost,
        retryAfterMs: 0,
        resetAfterMs: 0,
        degraded: true,
      };
    }
    // LOCAL: an exact limit per instance (N instances may allow up to N x limit in total)
    const d = await this.local.check(tenant.id, resource, tenant.policy, cost);
    return { ...d, degraded: true };
  }

  /** Middleware style: a denied request becomes a 429 problem with Retry-After. */
  async enforce(tenant: Tenant, resource: string = DEMO_RESOURCE): Promise<Verdict> {
    const verdict = await this.decide(tenant, resource, 1);
    if (!verdict.decision.allowed) throw LimiterService.tooMany(verdict);
    return verdict;
  }

  static tooMany(verdict: Verdict): ApiError {
    const retryS = Math.max(1, secondsUp(verdict.decision.retryAfterMs));
    const headers = { ...rateLimitHeaders(verdict), "Retry-After": String(retryS) };
    const { limit, windowSeconds } = verdict.tenant.policy;
    return rateLimited(limit, windowSeconds, retryS, headers);
  }
}

export function rateLimitHeaders(verdict: Verdict): Record<string, string> {
  const d = verdict.decision;
  const headers: Record<string, string> = {
    "X-RateLimit-Limit": String(verdict.tenant.policy.limit),
    "X-RateLimit-Remaining": String(d.remaining),
    "X-RateLimit-Reset": String(secondsUp(d.resetAfterMs)),
  };
  if (d.degraded) headers["X-RateLimit-Degraded"] = "true";
  return headers;
}
