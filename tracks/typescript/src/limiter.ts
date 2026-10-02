// M4: the limiter service: validate a check, ask the store, turn the decision into HTTP headers.
import { ApiError, costExceedsLimit, limiterUnavailable, rateLimited, StoreUnavailable } from "./errors.js";
import type { Tenant, Verdict } from "./models.js";
import type { Store } from "./store.js";
import * as v from "./validation.js";

export const DEMO_RESOURCE = "demo";

export const secondsUp = (ms: number): number => Math.ceil(ms / 1000);

export class LimiterService {
  constructor(readonly store: Store) {}

  /** POST /limits/check: the decision API other services call before doing the work. */
  async check(tenant: Tenant, payload: unknown): Promise<Verdict> {
    const body = payload === undefined || payload === null ? {} : v.requireObject(payload);
    return this.decide(tenant, v.resource(body.resource), v.cost(body.cost));
  }

  async decide(tenant: Tenant, resource: string, cost: number): Promise<Verdict> {
    if (cost > tenant.policy.limit) throw costExceedsLimit(cost, tenant.policy.limit);
    try {
      const decision = await this.store.check(tenant.id, resource, tenant.policy, cost);
      return { tenant, resource, cost, decision };
    } catch (err) {
      // v1.0 fails CLOSED: without its store the limiter cannot promise anything (ADR-0004).
      if (err instanceof StoreUnavailable) throw limiterUnavailable();
      throw err;
    }
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
  return {
    "X-RateLimit-Limit": String(verdict.tenant.policy.limit),
    "X-RateLimit-Remaining": String(d.remaining),
    "X-RateLimit-Reset": String(secondsUp(d.resetAfterMs)),
  };
}
