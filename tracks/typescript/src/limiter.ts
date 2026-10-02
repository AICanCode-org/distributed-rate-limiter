// M4: the limiter service: validate a check, ask the store, turn the decision into HTTP headers.
import { ApiError } from "./errors.js";
import type { Tenant, Verdict } from "./models.js";
import type { Store } from "./store.js";

export const DEMO_RESOURCE = "demo";

export function secondsUp(ms: number): number {
  // TODO(phase-3 M4): ms -> whole seconds, rounded UP (1 ms -> 1 s, 0 -> 0).
  throw new Error("TODO(phase-3 M4): secondsUp");
}

export class LimiterService {
  constructor(readonly store: Store) {}

  /** POST /limits/check: the decision API other services call before doing the work. */
  async check(tenant: Tenant, payload: unknown): Promise<Verdict> {
    // TODO(phase-3 M4): POST /limits/check body: missing/null -> {}; otherwise an object with optional
    //   resource and cost (validation); then decide().
    throw new Error("TODO(phase-3 M4): LimiterService.check");
  }

  async decide(tenant: Tenant, resource: string, cost: number): Promise<Verdict> {
    // TODO(phase-3 M4): cost above the tenant's limit -> 422 cost-exceeds-limit (it could never be allowed);
    //   ask the store; StoreUnavailable -> 503 limiter-unavailable (fail CLOSED, ADR-0004).
    throw new Error("TODO(phase-3 M4): LimiterService.decide");
  }

  /** Middleware style: a denied request becomes a 429 problem with Retry-After. */
  async enforce(tenant: Tenant, resource: string = DEMO_RESOURCE): Promise<Verdict> {
    // TODO(phase-3 M4): decide(tenant, resource, cost 1); denied -> raise too_many(verdict).
    throw new Error("TODO(phase-3 M4): LimiterService.enforce");
  }

  static tooMany(verdict: Verdict): ApiError {
    // TODO(phase-3 M4): 429 rate-limited with the X-RateLimit-* headers and Retry-After = max(1,
    //   seconds_up(retryAfterMs)).
    throw new Error("TODO(phase-3 M4): LimiterService.tooMany");
  }
}

export function rateLimitHeaders(verdict: Verdict): Record<string, string> {
  // TODO(phase-3 M4): X-RateLimit-Limit (the policy limit), X-RateLimit-Remaining, X-RateLimit-Reset
  //   (seconds_up(resetAfterMs)).
  throw new Error("TODO(phase-3 M4): rateLimitHeaders");
}
