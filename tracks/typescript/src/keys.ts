// M3: the Redis key layout (spec/architecture.md). One place, so no module builds keys by hand.
//
// The {tenant} hash tag makes every limiter key of one tenant hash to the same Redis Cluster slot,
// so a Lua script that touches the bucket and the usage ring stays legal on a cluster (ADR-0003).
import { TOKEN_BUCKET, type Algorithm } from "./models.js";

export const TENANTS = "rg:tenants";
/** minutes kept in the usage ring (3 hours); the API reads at most 120 */
export const USAGE_SLOTS = 180;

export const tenantKey = (tenantId: string) => `rg:tenant:${tenantId}`;
export const apiKeyKey = (keyHash: string) => `rg:key:${keyHash}`;
export const limiterKey = (tenantId: string, algorithm: Algorithm, resource: string) =>
  `rg:{${tenantId}}:${algorithm === TOKEN_BUCKET ? "tb" : "sw"}:${resource}`;
export const usageKey = (tenantId: string) => `rg:{${tenantId}}:usage`;
/** SCAN pattern for every limiter key of one tenant (and nobody else's: ids cannot contain '}'). */
export const tenantPattern = (tenantId: string) => `rg:{${tenantId}}:*`;
export const usageSlot = (minute: number) => minute % USAGE_SLOTS;
