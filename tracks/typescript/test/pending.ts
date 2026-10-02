import { describe } from "vitest";

// ---------------------------------------------------------------------------
// PENDING topics describe behaviour you have not built yet, so their tests are
// skipped (vitest reports them as "skipped"). When you start a milestone, delete
// its line here, watch the tests fail (red), implement until they pass (green).
// See docs/phase-3.
// ---------------------------------------------------------------------------
export const PENDING = new Set<string>([
  "validation", // Phase 3 M1 - validation
  "security", // Phase 3 M1 - API keys and the admin token
  "token-bucket", // Phase 3 M2 - token bucket
  "sliding-window", // Phase 3 M2 - sliding window counter
  "memory-store", // Phase 3 M2 - the in-memory store on your algorithms
  "keys", // Phase 3 M3 - Redis key layout
  "tenants", // Phase 3 M4 - TenantService
  "limiter", // Phase 3 M4 - LimiterService
  "api-admin", // Phase 3 M4 - admin endpoints
  "api-limits", // Phase 3 M4 - limiter endpoints
  "advisor", // Phase 3 M5 - usage stats and the rule advisor
  "api-advice", // Phase 3 M5 - advice endpoint
  "integration", // Phase 4 - integration tests
]);

/** `const describe = pendingDescribe("codes")` - a describe that is skipped while the topic is pending. */
export const pendingDescribe = (topic: string) => describe.skipIf(PENDING.has(topic));
