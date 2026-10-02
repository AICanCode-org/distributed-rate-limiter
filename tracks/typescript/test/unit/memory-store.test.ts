// M2: the in-memory store (given) runs your algorithms. These tests pin down what a store must do;
// RedisStore (M3) must behave the same, which the integration tests check in Phase 4.
import { expect, it } from "vitest";
import { DuplicateError, StoreUnavailable } from "../../src/errors.js";
import type { Policy, Tenant } from "../../src/models.js";
import { InMemoryStore } from "../../src/store.js";
import { FakeClock } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("memory-store");
const TB: Policy = { algorithm: "TOKEN_BUCKET", limit: 2, windowSeconds: 60, failMode: "CLOSED" };
const SW: Policy = { algorithm: "SLIDING_WINDOW", limit: 2, windowSeconds: 60, failMode: "CLOSED" };
const NOW = new Date("2026-10-02T09:30:00.000Z");
const tenant = (id = "acme", keyHash = "h1"): Tenant => ({
  id,
  name: "Acme",
  policy: TB,
  keyHash,
  keyPrefix: "rg_000000",
  createdAt: NOW,
  updatedAt: NOW,
});

function setup() {
  const clock = new FakeClock();
  return { clock, store: new InMemoryStore(clock.fn) };
}

describe("in-memory store", () => {
  it("creates, finds, lists and refuses duplicates", async () => {
    const { store } = setup();
    await store.createTenant(tenant("beta", "h2"));
    await store.createTenant(tenant("acme", "h1"));
    expect((await store.listTenants()).map((t) => t.id)).toEqual(["acme", "beta"]);
    expect((await store.findByKeyHash("h2"))!.id).toBe("beta");
    expect(await store.findByKeyHash("nope")).toBeNull();
    await expect(store.createTenant(tenant("acme", "h9"))).rejects.toBeInstanceOf(DuplicateError);
  });

  it("moves the key on save", async () => {
    const { store } = setup();
    const t = tenant();
    await store.createTenant(t);
    await store.saveTenant({ ...t, keyHash: "h-new", keyPrefix: "rg_111111" }, t.keyHash);
    expect(await store.findByKeyHash("h1")).toBeNull();
    expect((await store.findByKeyHash("h-new"))!.id).toBe("acme");
  });

  it("keeps buckets per tenant and resource", async () => {
    const { store } = setup();
    expect((await store.check("acme", "a", TB, 2)).allowed).toBe(true);
    expect((await store.check("acme", "a", TB, 1)).allowed).toBe(false);
    expect((await store.check("acme", "b", TB, 1)).allowed).toBe(true);
    expect((await store.check("beta", "a", TB, 1)).allowed).toBe(true);
  });

  it("keeps separate state per algorithm", async () => {
    const { store } = setup();
    await store.check("acme", "a", TB, 2);
    expect(await store.check("acme", "a", SW, 2)).toEqual({
      allowed: true,
      remaining: 0,
      retryAfterMs: 0,
      resetAfterMs: 120_000,
    });
  });

  it("uses the injected clock", async () => {
    const { store, clock } = setup();
    await store.check("acme", "a", TB, 2);
    clock.advance({ seconds: 30 });
    expect((await store.check("acme", "a", TB, 1)).allowed).toBe(true);
  });

  it("counts allowed and denied per minute", async () => {
    const { store, clock } = setup();
    for (let i = 0; i < 3; i++) await store.check("acme", "a", TB, 1);
    clock.advance({ minutes: 2 });
    await store.check("acme", "a", TB, 1);
    const points = await store.usage("acme", 3);
    expect(points.map((p) => [p.allowed, p.denied])).toEqual([
      [2, 1],
      [0, 0],
      [1, 0],
    ]);
    expect(points.at(-1)!.minute).toBe(Math.floor(clock.now.getTime() / 60_000));
  });

  it("resets only that tenant", async () => {
    const { store } = setup();
    await store.check("acme", "a", TB, 2);
    await store.check("acme-2", "a", TB, 2);
    expect(await store.reset("acme")).toBe(1);
    expect((await store.check("acme", "a", TB, 1)).allowed).toBe(true);
    expect((await store.check("acme-2", "a", TB, 1)).allowed).toBe(false);
  });

  it("deletes the tenant, its key and its state", async () => {
    const { store } = setup();
    const t = tenant();
    await store.createTenant(t);
    await store.check("acme", "a", TB, 2);
    await store.deleteTenant(t);
    expect(await store.getTenant("acme")).toBeNull();
    expect(await store.findByKeyHash("h1")).toBeNull();
    expect((await store.check("acme", "a", TB, 1)).remaining).toBe(1);
  });

  it("throws StoreUnavailable during an outage", async () => {
    const { store } = setup();
    store.up = false;
    expect(await store.ping()).toBe(false);
    await expect(store.check("acme", "a", TB, 1)).rejects.toBeInstanceOf(StoreUnavailable);
    await expect(store.getTenant("acme")).rejects.toBeInstanceOf(StoreUnavailable);
  });
});
