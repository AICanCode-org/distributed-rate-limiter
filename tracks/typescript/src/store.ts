// Storage behind one interface: InMemoryStore (unit tests; v1.1 degraded mode) and RedisStore (M3).
// A store keeps tenants, answers limiter checks atomically and counts usage per minute.
import { Redis } from "ioredis";
import { slidingWindow, tokenBucket, type BucketState, type WindowState } from "./algorithms.js";
import { systemClock, toMs, type Clock } from "./clock.js";
import { DuplicateError, StoreUnavailable } from "./errors.js";
import * as keys from "./keys.js";
import {
  TOKEN_BUCKET,
  windowMs,
  type Decision,
  type Policy,
  type Tenant,
  type UsagePoint,
} from "./models.js";

/** Starter helper: marks code you still have to write (returns `never`, so types still check). */
const todo = (what: string): never => {
  throw new Error(what);
};

export interface Store {
  ping(): Promise<boolean>;
  /** DuplicateError when the id is taken */
  createTenant(tenant: Tenant): Promise<void>;
  getTenant(tenantId: string): Promise<Tenant | null>;
  listTenants(): Promise<Tenant[]>;
  findByKeyHash(keyHash: string): Promise<Tenant | null>;
  /** policy, name or key changed */
  saveTenant(tenant: Tenant, oldKeyHash: string): Promise<void>;
  deleteTenant(tenant: Tenant): Promise<void>;
  /** number of limiter keys removed */
  reset(tenantId: string): Promise<number>;
  check(tenantId: string, resource: string, policy: Policy, cost: number): Promise<Decision>;
  /** oldest first, zeros included */
  usage(tenantId: string, minutes: number): Promise<UsagePoint[]>;
}

// ---------------------------------------------------------------------------------------------------
/**
 * Everything in maps, using the injected clock (JavaScript is single-threaded, so no lock is needed).
 * Read it: it shows exactly what each RedisStore method must do.
 */
export class InMemoryStore implements Store {
  readonly tenants = new Map<string, Tenant>();
  /** key hash -> tenant id */
  readonly keys = new Map<string, string>();
  readonly limiters = new Map<string, BucketState | WindowState>();
  /** tenant -> minute -> [allowed, denied] */
  readonly usageCounts = new Map<string, Map<number, [number, number]>>();
  /** tests flip this to simulate an outage */
  up = true;

  constructor(private readonly clock: Clock = systemClock) {}

  private guard() {
    if (!this.up) throw new StoreUnavailable("in-memory store is down (test)");
  }

  async ping() {
    return this.up;
  }

  async createTenant(tenant: Tenant) {
    this.guard();
    if (this.tenants.has(tenant.id)) throw new DuplicateError(tenant.id);
    this.tenants.set(tenant.id, tenant);
    this.keys.set(tenant.keyHash, tenant.id);
  }

  async getTenant(tenantId: string) {
    this.guard();
    return this.tenants.get(tenantId) ?? null;
  }

  async listTenants() {
    this.guard();
    return [...this.tenants.values()].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  }

  async findByKeyHash(keyHash: string) {
    this.guard();
    const id = this.keys.get(keyHash);
    return id === undefined ? null : (this.tenants.get(id) ?? null);
  }

  async saveTenant(tenant: Tenant, oldKeyHash: string) {
    this.guard();
    if (oldKeyHash !== tenant.keyHash) {
      this.keys.delete(oldKeyHash);
      this.keys.set(tenant.keyHash, tenant.id);
    }
    this.tenants.set(tenant.id, tenant);
  }

  async deleteTenant(tenant: Tenant) {
    this.guard();
    this.tenants.delete(tenant.id);
    this.keys.delete(tenant.keyHash);
    this.usageCounts.delete(tenant.id);
    this.resetNow(tenant.id);
  }

  async reset(tenantId: string) {
    this.guard();
    return this.resetNow(tenantId);
  }

  private resetNow(tenantId: string): number {
    const prefix = keys.tenantPattern(tenantId).slice(0, -1);
    const doomed = [...this.limiters.keys()].filter((k) => k.startsWith(prefix));
    for (const k of doomed) this.limiters.delete(k);
    return doomed.length;
  }

  async check(tenantId: string, resource: string, policy: Policy, cost: number): Promise<Decision> {
    this.guard();
    const now = toMs(this.clock());
    const key = keys.limiterKey(tenantId, policy.algorithm, resource);
    const state = this.limiters.get(key) ?? null;
    const [next, decision] =
      policy.algorithm === TOKEN_BUCKET
        ? tokenBucket(state as BucketState | null, policy.limit, windowMs(policy), cost, now)
        : slidingWindow(state as WindowState | null, policy.limit, windowMs(policy), cost, now);
    this.limiters.set(key, next);
    let perTenant = this.usageCounts.get(tenantId);
    if (!perTenant) this.usageCounts.set(tenantId, (perTenant = new Map()));
    const minute = Math.floor(now / 60_000);
    const counts = perTenant.get(minute) ?? [0, 0];
    counts[decision.allowed ? 0 : 1] += 1;
    perTenant.set(minute, counts);
    return decision;
  }

  async usage(tenantId: string, minutes: number): Promise<UsagePoint[]> {
    this.guard();
    const current = Math.floor(toMs(this.clock()) / 60_000);
    const counts = this.usageCounts.get(tenantId);
    const out: UsagePoint[] = [];
    for (let minute = current - minutes + 1; minute <= current; minute++) {
      const [allowed, denied] = counts?.get(minute) ?? [0, 0];
      out.push({ minute, allowed, denied });
    }
    return out;
  }
}

// ---------------------------------------------------------------------------------------------------
export function tenantToHash(t: Tenant): Record<string, string> {
  // TODO(phase-3 M3): the tenant as a flat string map for HSET: id, name, algorithm, limit, windowSeconds,
  //   keyHash, keyPrefix, createdAt/updatedAt as epoch ms.
  throw new Error("TODO(phase-3 M3): tenantToHash");
}

export function tenantFromHash(h: Record<string, string>): Tenant {
  // TODO(phase-3 M3): the reverse of tenant_to_hash (numbers parsed, epoch ms back to UTC datetimes).
  throw new Error("TODO(phase-3 M3): tenantFromHash");
}

export type LimiterScript = (
  k1: string,
  k2: string,
  ...args: (string | number)[]
) => Promise<[number, number, number, number]>;

/** M3: the real store. Limiter checks are ONE Lua script call each (atomic, one round trip). */
export class RedisStore implements Store {
  constructor(
    readonly redis: Redis,
    readonly luaDir: string,
  ) {
    // TODO(phase-3 M3): load spec/lua/token_bucket.lua and sliding_window.lua (you wrote them in Phase 2)
    //   with redis.defineCommand(name, { numberOfKeys: 2, lua }): it sends EVALSHA and falls back to EVAL
    //   when Redis answers NOSCRIPT.
  }

  static fromUrl(url: string, timeoutMs: number, luaDir: string): RedisStore {
    const redis = new Redis(url, {
      commandTimeout: timeoutMs,
      connectTimeout: timeoutMs,
      maxRetriesPerRequest: 1, // fail fast: the caller decides what an outage means
      lazyConnect: true,
    });
    redis.on("error", () => {}); // errors reach the caller of each command; no unhandled 'error' events
    return new RedisStore(redis, luaDir);
  }

  private async call<T>(fn: () => Promise<T>): Promise<T> {
    try {
      return await fn();
    } catch (err) {
      if (err instanceof DuplicateError) throw err;
      throw new StoreUnavailable(err instanceof Error ? err.message : String(err));
    }
  }

  async ping() {
    try {
      return (await this.redis.ping()) === "PONG";
    } catch {
      return false;
    }
  }

  async createTenant(tenant: Tenant): Promise<void> {
    // TODO(phase-3 M3): SADD rg:tenants <id>; 0 means the id exists -> DuplicateError (Redis decides the
    //   race, not the app). Then in ONE MULTI/EXEC: HSET the tenant hash and SET rg:key:<hash> <id>.
    return todo("TODO(phase-3 M3): RedisStore.createTenant");
  }

  async getTenant(tenantId: string): Promise<Tenant | null> {
    // TODO(phase-3 M3): HGETALL rg:tenant:<id>; empty -> null.
    return todo("TODO(phase-3 M3): RedisStore.getTenant");
  }

  async listTenants(): Promise<Tenant[]> {
    // TODO(phase-3 M3): SMEMBERS rg:tenants, sorted by id, then HGETALL each one in a pipeline.
    return todo("TODO(phase-3 M3): RedisStore.listTenants");
  }

  async findByKeyHash(keyHash: string): Promise<Tenant | null> {
    // TODO(phase-3 M3): GET rg:key:<hash> -> tenant id -> get_tenant; unknown -> null.
    return todo("TODO(phase-3 M3): RedisStore.findByKeyHash");
  }

  async saveTenant(tenant: Tenant, oldKeyHash: string): Promise<void> {
    // TODO(phase-3 M3): ONE MULTI/EXEC: HSET the hash; if the key hash changed, DEL the old rg:key:<old> and
    //   SET the new one (the old key stops working at once).
    return todo("TODO(phase-3 M3): RedisStore.saveTenant");
  }

  async deleteTenant(tenant: Tenant): Promise<void> {
    // TODO(phase-3 M3): ONE MULTI/EXEC: DEL the tenant hash, its rg:key and its usage ring, SREM from
    //   rg:tenants; then reset() the limiter keys.
    return todo("TODO(phase-3 M3): RedisStore.deleteTenant");
  }

  async reset(tenantId: string): Promise<number> {
    // TODO(phase-3 M3): SCAN (never KEYS) for tenant_pattern, keep the usage ring, DEL the rest; return how
    //   many were deleted.
    return todo("TODO(phase-3 M3): RedisStore.reset");
  }

  async check(
    tenantId: string,
    resource: string,
    policy: Policy,
    cost: number,
    nowMs?: number,
  ): Promise<Decision> {
    // TODO(phase-3 M3): ONE script call (EVALSHA, EVAL on NOSCRIPT): token_bucket.lua or sliding_window.lua
    //   with KEYS = [limiter key, usage key] and ARGV = [limit, window_ms, cost (, now_ms for tests)]; it
    //   returns [allowed 0/1, remaining, retryAfterMs, resetAfterMs].
    throw new Error("TODO(phase-3 M3): RedisStore.check");
  }

  async usage(tenantId: string, minutes: number): Promise<UsagePoint[]> {
    // TODO(phase-3 M3): TIME gives Redis' clock; HGETALL the ring; for each of the last n minutes slot =
    //   usage_slot(minute): use a<slot>/d<slot> only if m<slot> == minute, else zeros. Oldest first.
    throw new Error("TODO(phase-3 M3): RedisStore.usage");
  }

  async close() {
    this.redis.disconnect();
  }
}
