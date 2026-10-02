// M3: the Redis key layout (spec/architecture.md). One place, so no module builds keys by hand.
//
// The {tenant} hash tag makes every limiter key of one tenant hash to the same Redis Cluster slot,
// so a Lua script that touches the bucket and the usage ring stays legal on a cluster (ADR-0003).
import { type Algorithm } from "./models.js";

export const TENANTS = "rg:tenants";
/** minutes kept in the usage ring (3 hours); the API reads at most 120 */
export const USAGE_SLOTS = 180;

export function tenantKey(tenantId: string): string {
  // TODO(phase-3 M3): 'rg:tenant:<id>' (a hash with the tenant's fields).
  throw new Error("TODO(phase-3 M3): tenantKey");
}
export function apiKeyKey(keyHash: string): string {
  // TODO(phase-3 M3): 'rg:key:<sha256 of the api key>' -> tenant id.
  throw new Error("TODO(phase-3 M3): apiKeyKey");
}
export function limiterKey(tenantId: string, algorithm: Algorithm, resource: string): string {
  // TODO(phase-3 M3): 'rg:{<id>}:tb:<resource>' for TOKEN_BUCKET, 'rg:{<id>}:sw:<resource>' for
  //   SLIDING_WINDOW. The {id} hash tag keeps a tenant's keys in one cluster slot (ADR-0003).
  throw new Error("TODO(phase-3 M3): limiterKey");
}
export function usageKey(tenantId: string): string {
  // TODO(phase-3 M3): 'rg:{<id>}:usage' (the per-minute usage ring, same hash tag as the limiter keys).
  throw new Error("TODO(phase-3 M3): usageKey");
}
/** SCAN pattern for every limiter key of one tenant (and nobody else's: ids cannot contain '}'). */
export function tenantPattern(tenantId: string): string {
  // TODO(phase-3 M3): SCAN pattern for every key of one tenant: 'rg:{<id>}:*' (ids cannot contain '}', so no
  //   other tenant matches).
  throw new Error("TODO(phase-3 M3): tenantPattern");
}
export function usageSlot(minute: number): number {
  // TODO(phase-3 M3): minute % USAGE_SLOTS (180): the ring slot of an epoch minute.
  throw new Error("TODO(phase-3 M3): usageSlot");
}
