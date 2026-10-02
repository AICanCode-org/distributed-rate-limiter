// Phase 4: the RedisStore and the shared Lua scripts against a real Redis.
// Needs Redis (docker compose up -d redis). Run with:  make test-integration
// Uses database 15 of TEST_REDIS_URL and FLUSHDB before each test, so never point it at data you care about.
import { afterAll, beforeEach, expect, it } from "vitest";
import { slidingWindow, tokenBucket } from "../../src/algorithms.js";
import { loadSettings } from "../../src/config.js";
import { DuplicateError, StoreUnavailable } from "../../src/errors.js";
import type { Algorithm, Policy, Tenant } from "../../src/models.js";
import { Metrics } from "../../src/metrics.js";
import { RedisStore } from "../../src/store.js";
import { ADMIN, Api, makeApp, testSettings } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("integration");
const URL = process.env.TEST_REDIS_URL ?? "redis://localhost:6379/15";
const LUA = loadSettings().luaDir;
const NOW = new Date("2026-10-02T09:30:00.000Z");
const TB5: Policy = { algorithm: "TOKEN_BUCKET", limit: 5, windowSeconds: 60, failMode: "CLOSED" };
const tenant = (id = "acme", keyHash = "h1", policy: Policy = TB5): Tenant => ({
  id,
  name: "Acme Corp",
  policy,
  keyHash,
  keyPrefix: "rg_000000",
  createdAt: NOW,
  updatedAt: NOW,
});

/** A tiny seeded random generator (mulberry32) so the parity walk is the same on every run. */
function rng(seed: number) {
  let a = seed;
  return <T>(items: T[]): T => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return items[Math.floor((((t ^ (t >>> 14)) >>> 0) / 4294967296) * items.length)]!;
  };
}

describe("RedisStore and the Lua scripts", () => {
  const store = RedisStore.fromUrl(URL, 2000, LUA);
  const opened: RedisStore[] = [store];
  const open = () => {
    const s = RedisStore.fromUrl(URL, 2000, LUA);
    opened.push(s);
    return s;
  };

  beforeEach(async () => {
    await store.redis.flushdb();
  });

  afterAll(async () => {
    for (const s of opened) await s.close();
  });

  it("pings", async () => {
    expect(await store.ping()).toBe(true);
  });

  it("round-trips a tenant", async () => {
    const t = tenant();
    await store.createTenant(t);
    expect(await store.getTenant("acme")).toEqual(t);
    expect(await store.findByKeyHash("h1")).toEqual(t);
    expect(await store.findByKeyHash("nope")).toBeNull();
    const rotated: Tenant = {
      ...t,
      policy: { algorithm: "SLIDING_WINDOW", limit: 9, windowSeconds: 10, failMode: "CLOSED" },
      keyHash: "h2",
      keyPrefix: "rg_111111",
    };
    await store.saveTenant(rotated, "h1");
    expect(await store.findByKeyHash("h1")).toBeNull();
    expect(await store.findByKeyHash("h2")).toEqual(rotated);
    await store.createTenant(tenant("beta", "h3"));
    expect((await store.listTenants()).map((x) => x.id)).toEqual(["acme", "beta"]);
    await store.deleteTenant(rotated);
    expect(await store.getTenant("acme")).toBeNull();
    expect(await store.findByKeyHash("h2")).toBeNull();
    expect(await store.redis.sismember("rg:tenants", "acme")).toBe(0);
  });

  it("lets exactly one of ten racing creates win", async () => {
    const stores = Array.from({ length: 10 }, open);
    const results = await Promise.allSettled(stores.map((s, i) => s.createTenant(tenant("acme", `h${i}`))));
    expect(results.filter((r) => r.status === "fulfilled")).toHaveLength(1);
    for (const r of results) if (r.status === "rejected") expect(r.reason).toBeInstanceOf(DuplicateError);
  });

  it.each<Algorithm>(["TOKEN_BUCKET", "SLIDING_WINDOW"])(
    "agrees with the pure function step by step (%s)",
    async (algorithm) => {
      const pick = rng(42);
      const policy: Policy = { algorithm, limit: 7, windowSeconds: 3, failMode: "CLOSED" };
      let now = 1_790_933_400_000;
      let state: never | null = null;
      for (let i = 0; i < 400; i++) {
        now += pick([0, 0, 1, 37, 250, 999, 1500, 4000]);
        const cost = pick([1, 1, 1, 2, 7]);
        const [next, expected] =
          algorithm === "TOKEN_BUCKET"
            ? tokenBucket(state, 7, 3000, cost, now)
            : slidingWindow(state, 7, 3000, cost, now);
        state = next as never;
        expect(await store.check("acme", "x", policy, cost, now), `${now} ${cost}`).toEqual(expected);
      }
    },
  );

  it.each<Algorithm>(["TOKEN_BUCKET", "SLIDING_WINDOW"])(
    "never exceeds the limit under concurrency (%s)",
    async (algorithm) => {
      const policy: Policy = { algorithm, limit: 10, windowSeconds: 60, failMode: "CLOSED" };
      const stores = Array.from({ length: 5 }, open);
      const results = await Promise.all(
        Array.from({ length: 60 }, (_, i) => stores[i % 5]!.check("acme", "x", policy, 1)),
      );
      expect(results.filter((d) => d.allowed)).toHaveLength(10);
    },
  );

  it("shares one limit between two app instances", async () => {
    const other = open(); // its own connection, like a second container
    const policy: Policy = { algorithm: "TOKEN_BUCKET", limit: 6, windowSeconds: 60, failMode: "CLOSED" };
    let allowed = 0;
    for (let i = 0; i < 12; i++)
      if ((await (i % 2 ? store : other).check("acme", "x", policy, 1)).allowed) allowed++;
    expect(allowed).toBe(6);
  });

  it("gives every key a TTL and records usage", async () => {
    await store.check(
      "acme",
      "x",
      { algorithm: "TOKEN_BUCKET", limit: 5, windowSeconds: 60, failMode: "CLOSED" },
      1,
    );
    await store.check(
      "acme",
      "y",
      { algorithm: "SLIDING_WINDOW", limit: 5, windowSeconds: 60, failMode: "CLOSED" },
      1,
    );
    const tb = await store.redis.pttl("rg:{acme}:tb:x");
    const sw = await store.redis.pttl("rg:{acme}:sw:y");
    const usage = await store.redis.pttl("rg:{acme}:usage");
    expect(tb > 0 && tb <= 13_000).toBe(true); // one token missing: full again in 12 s (+1 s)
    expect(sw > 0 && sw <= 121_000).toBe(true);
    expect(usage > 0 && usage <= 10_800_000).toBe(true);
    const last = (await store.usage("acme", 2)).at(-1)!;
    expect([last.allowed, last.denied]).toEqual([2, 0]);
  });

  it("reads the usage ring for the current minutes", async () => {
    const policy: Policy = { algorithm: "TOKEN_BUCKET", limit: 1, windowSeconds: 3600, failMode: "CLOSED" };
    for (let i = 0; i < 3; i++) await store.check("acme", "x", policy, 1);
    const points = await store.usage("acme", 120);
    expect(points).toHaveLength(120);
    expect([points.at(-1)!.allowed, points.at(-1)!.denied]).toEqual([1, 2]);
    expect(points.slice(0, -1).reduce((s, p) => s + p.allowed + p.denied, 0)).toBe(0);
    expect(points.at(-1)!.minute - points[0]!.minute).toBe(119);
  });

  it("resets one tenant only", async () => {
    const policy: Policy = { algorithm: "TOKEN_BUCKET", limit: 1, windowSeconds: 3600, failMode: "CLOSED" };
    for (const id of ["acme", "acme-2"]) await store.check(id, "x", policy, 1);
    expect(await store.reset("acme")).toBe(1);
    expect((await store.check("acme", "x", policy, 1)).allowed).toBe(true);
    expect((await store.check("acme-2", "x", policy, 1)).allowed).toBe(false);
    expect(await store.redis.exists("rg:{acme}:usage")).toBe(1); // history survives a reset
  });

  it("uses the Redis clock without an override", async () => {
    const policy: Policy = { algorithm: "TOKEN_BUCKET", limit: 2, windowSeconds: 1, failMode: "CLOSED" };
    const out = [];
    for (let i = 0; i < 3; i++) out.push((await store.check("acme", "x", policy, 1)).allowed);
    expect(out).toEqual([true, true, false]);
    await new Promise((r) => setTimeout(r, 600));
    expect((await store.check("acme", "x", policy, 1)).allowed).toBe(true);
  });

  it("reloads the scripts after a SCRIPT FLUSH", async () => {
    const policy: Policy = { algorithm: "SLIDING_WINDOW", limit: 3, windowSeconds: 60, failMode: "CLOSED" };
    await store.check("acme", "x", policy, 1);
    await store.redis.script("FLUSH");
    expect((await store.check("acme", "x", policy, 1)).remaining).toBe(1);
  });

  it("reports an unreachable Redis as StoreUnavailable", async () => {
    const dead = RedisStore.fromUrl("redis://127.0.0.1:1/0", 300, LUA);
    opened.push(dead);
    expect(await dead.ping()).toBe(false);
    await expect(
      dead.check(
        "acme",
        "x",
        { algorithm: "TOKEN_BUCKET", limit: 1, windowSeconds: 1, failMode: "CLOSED" },
        1,
      ),
    ).rejects.toBeInstanceOf(StoreUnavailable);
    await expect(dead.getTenant("acme")).rejects.toBeInstanceOf(StoreUnavailable);
  });

  it("runs the whole API on Redis", async () => {
    const app = makeApp(open(), null);
    const api = new Api(app);
    const key = (await api.tenant("acme", "TOKEN_BUCKET", 3)).apiKey;
    const codes = [];
    for (let i = 0; i < 4; i++) codes.push((await api.hello(key)).statusCode);
    expect(codes).toEqual([200, 200, 200, 429]);
    const usage = (
      await app.inject({ method: "GET", url: "/api/v1/admin/tenants/acme/usage?minutes=1", headers: ADMIN })
    ).json();
    expect(usage.totals).toEqual({ allowed: 3, denied: 1 });
    expect((await app.inject({ method: "GET", url: "/health" })).json()).toEqual({
      status: "UP",
      checks: { redis: "UP" },
    });
  });

  it("handles parallel HTTP requests on Redis", async () => {
    const app = makeApp(open(), null);
    const api = new Api(app);
    const key = (await api.tenant("acme", "SLIDING_WINDOW", 8, 60)).apiKey;
    const codes = (await Promise.all(Array.from({ length: 30 }, () => api.hello(key)))).map(
      (r) => r.statusCode,
    );
    expect(codes.filter((c) => c === 200)).toHaveLength(8);
    expect(codes.filter((c) => c === 429)).toHaveLength(22);
  });

  // ---- v1.1 (Phase 7) ----------------------------------------------------------------------------
  it("stores failMode and reads a v1.0 hash as CLOSED", async () => {
    await store.createTenant(tenant("acme", "h1", { ...TB5, failMode: "LOCAL" }));
    expect((await store.getTenant("acme"))!.policy.failMode).toBe("LOCAL");
    await store.redis.hdel("rg:tenant:acme", "failMode"); // what a tenant written by v1.0 looks like
    expect((await store.getTenant("acme"))!.policy.failMode).toBe("CLOSED");
  });

  it("costs three failures on a dead Redis, then answers fast", async () => {
    const metrics = new Metrics();
    const dead = RedisStore.fromUrl("redis://127.0.0.1:1/0", 300, LUA);
    opened.push(dead);
    const app = makeApp(dead, null, undefined, testSettings({ redisTimeoutMs: 300 }), { metrics });
    const started = Date.now();
    for (let i = 0; i < 20; i++) {
      expect((await app.inject({ method: "GET", url: "/health" })).json().status).toBe("DEGRADED");
    }
    expect(Date.now() - started).toBeLessThan(5000);
    expect(metrics.value("rateguard_store_errors_total")).toBe(3);
    expect(metrics.value("rateguard_store_short_circuits_total")).toBe(17);
  });
});
