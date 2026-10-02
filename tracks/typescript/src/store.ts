// Storage behind one interface: InMemoryStore (unit tests; v1.1 degraded mode) and RedisStore (M3).
// A store keeps tenants, answers limiter checks atomically and counts usage per minute.
import { readFileSync } from "node:fs";
import path from "node:path";
import { Redis } from "ioredis";
import { slidingWindow, tokenBucket, type BucketState, type WindowState } from "./algorithms.js";
import { systemClock, toMs, type Clock } from "./clock.js";
import { CircuitOpenError, type CircuitBreaker } from "./breaker.js";
import { DuplicateError, StoreUnavailable } from "./errors.js";
import * as keys from "./keys.js";
import {
  CLOSED,
  TOKEN_BUCKET,
  type FailMode,
  windowMs,
  type Decision,
  type Policy,
  type Tenant,
  type UsagePoint,
} from "./models.js";

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
  return {
    id: t.id,
    name: t.name,
    algorithm: t.policy.algorithm,
    limit: String(t.policy.limit),
    windowSeconds: String(t.policy.windowSeconds),
    failMode: t.policy.failMode,
    keyHash: t.keyHash,
    keyPrefix: t.keyPrefix,
    createdAt: String(toMs(t.createdAt)),
    updatedAt: String(toMs(t.updatedAt)),
  };
}

export function tenantFromHash(h: Record<string, string>): Tenant {
  return {
    id: h.id!,
    name: h.name!,
    policy: {
      algorithm: h.algorithm as Policy["algorithm"],
      limit: Number(h.limit),
      windowSeconds: Number(h.windowSeconds),
      // A tenant written by v1.0 has no failMode: it keeps the v1.0 behaviour (CLOSED).
      failMode: (h.failMode ?? CLOSED) as FailMode,
    },
    keyHash: h.keyHash!,
    keyPrefix: h.keyPrefix!,
    createdAt: new Date(Number(h.createdAt)),
    updatedAt: new Date(Number(h.updatedAt)),
  };
}

type LimiterScript = (
  k1: string,
  k2: string,
  ...args: (string | number)[]
) => Promise<[number, number, number, number]>;

/** M3: the real store. Limiter checks are ONE Lua script call each (atomic, one round trip). */
export class RedisStore implements Store {
  constructor(
    readonly redis: Redis,
    luaDir: string,
  ) {
    // defineCommand sends EVALSHA and falls back to EVAL when Redis answers NOSCRIPT.
    for (const [name, file] of [
      ["rgTokenBucket", "token_bucket.lua"],
      ["rgSlidingWindow", "sliding_window.lua"],
    ] as const) {
      redis.defineCommand(name, { numberOfKeys: 2, lua: readFileSync(path.join(luaDir, file), "utf8") });
    }
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

  async createTenant(tenant: Tenant) {
    await this.call(async () => {
      // SADD answers 0 when the id is already a member: Redis, not the app, decides the race.
      if ((await this.redis.sadd(keys.TENANTS, tenant.id)) === 0) throw new DuplicateError(tenant.id);
      await this.redis
        .multi()
        .hset(keys.tenantKey(tenant.id), tenantToHash(tenant))
        .set(keys.apiKeyKey(tenant.keyHash), tenant.id)
        .exec();
    });
  }

  async getTenant(tenantId: string) {
    const h = await this.call(() => this.redis.hgetall(keys.tenantKey(tenantId)));
    return Object.keys(h).length ? tenantFromHash(h) : null;
  }

  async listTenants() {
    return this.call(async () => {
      const ids = (await this.redis.smembers(keys.TENANTS)).sort();
      const pipe = this.redis.pipeline();
      for (const id of ids) pipe.hgetall(keys.tenantKey(id));
      const results = (await pipe.exec()) ?? [];
      return results
        .map(([err, h]) => {
          if (err) throw err;
          return h as Record<string, string>;
        })
        .filter((h) => Object.keys(h).length > 0)
        .map(tenantFromHash);
    });
  }

  async findByKeyHash(keyHash: string) {
    const id = await this.call(() => this.redis.get(keys.apiKeyKey(keyHash)));
    return id ? this.getTenant(id) : null;
  }

  async saveTenant(tenant: Tenant, oldKeyHash: string) {
    await this.call(async () => {
      const tx = this.redis.multi().hset(keys.tenantKey(tenant.id), tenantToHash(tenant)); // MULTI/EXEC: all or nothing
      if (oldKeyHash !== tenant.keyHash) {
        tx.del(keys.apiKeyKey(oldKeyHash)).set(keys.apiKeyKey(tenant.keyHash), tenant.id);
      }
      await tx.exec();
    });
  }

  async deleteTenant(tenant: Tenant) {
    await this.call(() =>
      this.redis
        .multi()
        .del(keys.tenantKey(tenant.id), keys.apiKeyKey(tenant.keyHash), keys.usageKey(tenant.id))
        .srem(keys.TENANTS, tenant.id)
        .exec(),
    );
    await this.reset(tenant.id);
  }

  async reset(tenantId: string) {
    return this.call(async () => {
      // SCAN walks the keyspace in small steps; KEYS would block Redis for everyone.
      const doomed: string[] = [];
      let cursor = "0";
      do {
        const [next, batch] = await this.redis.scan(
          cursor,
          "MATCH",
          keys.tenantPattern(tenantId),
          "COUNT",
          500,
        );
        cursor = next;
        doomed.push(...batch.filter((k) => k !== keys.usageKey(tenantId)));
      } while (cursor !== "0");
      return doomed.length ? await this.redis.del(...new Set(doomed)) : 0;
    });
  }

  async check(
    tenantId: string,
    resource: string,
    policy: Policy,
    cost: number,
    nowMs?: number,
  ): Promise<Decision> {
    const script = (this.redis as unknown as Record<string, LimiterScript>)[
      policy.algorithm === TOKEN_BUCKET ? "rgTokenBucket" : "rgSlidingWindow"
    ]!;
    const args: number[] = [policy.limit, windowMs(policy), cost, ...(nowMs === undefined ? [] : [nowMs])];
    const [allowed, remaining, retry, reset] = await this.call(() =>
      script.call(
        this.redis,
        keys.limiterKey(tenantId, policy.algorithm, resource),
        keys.usageKey(tenantId),
        ...args,
      ),
    );
    return { allowed: allowed === 1, remaining, retryAfterMs: retry, resetAfterMs: reset };
  }

  async usage(tenantId: string, minutes: number): Promise<UsagePoint[]> {
    return this.call(async () => {
      const [seconds, micros] = await this.redis.time();
      const current = Math.floor((Number(seconds) * 1000 + Math.floor(Number(micros) / 1000)) / 60_000);
      const ring = await this.redis.hgetall(keys.usageKey(tenantId));
      const out: UsagePoint[] = [];
      for (let minute = current - minutes + 1; minute <= current; minute++) {
        const slot = keys.usageSlot(minute);
        if (ring[`m${slot}`] === String(minute)) {
          out.push({ minute, allowed: Number(ring[`a${slot}`] ?? 0), denied: Number(ring[`d${slot}`] ?? 0) });
        } else {
          out.push({ minute, allowed: 0, denied: 0 });
        }
      }
      return out;
    });
  }

  async close() {
    this.redis.disconnect();
  }
}

// ---------------------------------------------------------------------------------------------------
/**
 * v1.1: any store behind a circuit breaker (ADR-0006).
 *
 * While Redis keeps failing, every call would wait for REDIS_TIMEOUT_MS before failing. After
 * `threshold` failures in a row the breaker opens and calls fail at once with StoreUnavailable,
 * so the limiter can answer from the tenant's failMode in microseconds instead of seconds.
 * DuplicateError is an answer from Redis, not a failure.
 */
export class GuardedStore implements Store {
  constructor(
    readonly inner: Store,
    readonly breaker: CircuitBreaker,
    private readonly onError: () => void = () => {},
  ) {}

  private async run<T>(fn: () => Promise<T>): Promise<T> {
    try {
      return await this.breaker.call(fn);
    } catch (err) {
      if (err instanceof CircuitOpenError) throw new StoreUnavailable(err.message);
      if (err instanceof StoreUnavailable) this.onError();
      throw err;
    }
  }

  async ping() {
    try {
      return await this.run(async () => {
        if (!(await this.inner.ping())) throw new StoreUnavailable("ping failed");
        return true;
      });
    } catch {
      return false;
    }
  }

  createTenant(tenant: Tenant) {
    return this.run(() => this.inner.createTenant(tenant));
  }

  getTenant(tenantId: string) {
    return this.run(() => this.inner.getTenant(tenantId));
  }

  listTenants() {
    return this.run(() => this.inner.listTenants());
  }

  findByKeyHash(keyHash: string) {
    return this.run(() => this.inner.findByKeyHash(keyHash));
  }

  saveTenant(tenant: Tenant, oldKeyHash: string) {
    return this.run(() => this.inner.saveTenant(tenant, oldKeyHash));
  }

  deleteTenant(tenant: Tenant) {
    return this.run(() => this.inner.deleteTenant(tenant));
  }

  reset(tenantId: string) {
    return this.run(() => this.inner.reset(tenantId));
  }

  check(tenantId: string, resource: string, policy: Policy, cost: number) {
    return this.run(() => this.inner.check(tenantId, resource, policy, cost));
  }

  usage(tenantId: string, minutes: number) {
    return this.run(() => this.inner.usage(tenantId, minutes));
  }
}
