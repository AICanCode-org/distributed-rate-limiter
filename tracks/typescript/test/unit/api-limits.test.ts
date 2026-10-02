// M4: the decision API and the rate-limited demo route over HTTP.
import { expect, it } from "vitest";
import { InMemoryStore } from "../../src/store.js";
import { Api, FakeClock, makeApp } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("api-limits");

function setup() {
  const clock = new FakeClock();
  const store = new InMemoryStore(clock.fn);
  const app = makeApp(store, clock);
  return { clock, store, app, api: new Api(app) };
}

describe("limits API", () => {
  it("returns the decision and the headers", async () => {
    const { api } = setup();
    const key = (await api.tenant("acme", "TOKEN_BUCKET", 5)).apiKey;
    const r = await api.check(key, { resource: "orders.create", cost: 2 });
    expect(r.statusCode).toBe(200);
    expect(r.json()).toEqual({
      allowed: true,
      tenantId: "acme",
      resource: "orders.create",
      cost: 2,
      algorithm: "TOKEN_BUCKET",
      limit: 5,
      remaining: 3,
      resetAfterMs: 24_000,
      retryAfterMs: 0,
      degraded: false,
    });
    expect(r.headers["x-ratelimit-degraded"]).toBeUndefined();
    expect(r.headers["x-ratelimit-limit"]).toBe("5");
    expect(r.headers["x-ratelimit-remaining"]).toBe("3");
    expect(r.headers["x-ratelimit-reset"]).toBe("24");
  });

  it("still answers a denied check with 200", async () => {
    const { api } = setup();
    const key = (await api.tenant("acme", "TOKEN_BUCKET", 1)).apiKey;
    await api.check(key);
    const r = await api.check(key);
    expect(r.statusCode).toBe(200);
    expect(r.json().allowed).toBe(false);
    expect(r.json().retryAfterMs).toBe(60_000);
    expect(r.headers["retry-after"]).toBeUndefined();
  });

  it("uses the defaults without a body", async () => {
    const { api } = setup();
    const key = (await api.tenant()).apiKey;
    const r = await api.check(key);
    expect(r.statusCode).toBe(200);
    expect(r.json().resource).toBe("default");
    expect(r.json().cost).toBe(1);
  });

  it("needs a valid API key", async () => {
    const { api, app } = setup();
    await api.tenant();
    for (const headers of [{}, { "x-api-key": "nope" }, { "x-api-key": "rg_" + "0".repeat(31) + "9" }]) {
      const r = await app.inject({ method: "POST", url: "/api/v1/limits/check", headers });
      expect(r.statusCode).toBe(401);
      expect(r.json().type).toMatch(/\/invalid-api-key$/);
      expect(r.headers["www-authenticate"]).toBeUndefined();
    }
  });

  it("reports check errors", async () => {
    const { api } = setup();
    const key = (await api.tenant("acme", "TOKEN_BUCKET", 3)).apiKey;
    expect((await api.check(key, { cost: 0 })).statusCode).toBe(400);
    const r = await api.check(key, { cost: 4 });
    expect(r.statusCode).toBe(422);
    expect(r.json().detail).toBe("cost 4 is more than the limit of 3");
  });

  it("works for a sliding window tenant", async () => {
    const { api, clock } = setup();
    const key = (await api.tenant("acme", "SLIDING_WINDOW", 2, 10)).apiKey;
    const out = [];
    for (let i = 0; i < 3; i++) out.push((await api.check(key)).json().allowed);
    expect(out).toEqual([true, true, false]);
    clock.advance({ seconds: 20 });
    expect((await api.check(key)).json().allowed).toBe(true);
  });

  it("rate-limits hello with 429 and Retry-After", async () => {
    const { api } = setup();
    const key = (await api.tenant("acme", "TOKEN_BUCKET", 2)).apiKey;
    const ok = await api.hello(key);
    expect(ok.statusCode).toBe(200);
    expect(ok.json()).toEqual({ message: "Hello, Acme Corp!", tenantId: "acme" });
    expect(ok.headers["x-ratelimit-remaining"]).toBe("1");
    await api.hello(key);
    const r = await api.hello(key);
    expect(r.statusCode).toBe(429);
    expect(r.headers["content-type"]).toContain("application/problem+json");
    expect(r.headers["retry-after"]).toBe("30");
    expect(r.headers["x-ratelimit-remaining"]).toBe("0");
    expect(r.json()).toEqual({
      type: "https://rateguard.example/problems/rate-limited",
      title: "Too many requests",
      status: 429,
      detail: "limit of 2 per 60 s reached, retry in 30 s",
    });
  });

  it("keeps hello and check in separate buckets", async () => {
    const { api } = setup();
    const key = (await api.tenant("acme", "TOKEN_BUCKET", 1)).apiKey;
    expect((await api.hello(key)).statusCode).toBe(200);
    expect((await api.check(key)).json().allowed).toBe(true); // resource "default", not "demo"
  });

  it("does not share limits between tenants", async () => {
    const { api } = setup();
    const a = (await api.tenant("acme", "TOKEN_BUCKET", 1)).apiKey;
    const b = (await api.tenant("beta", "TOKEN_BUCKET", 1)).apiKey;
    expect((await api.hello(a)).statusCode).toBe(200);
    expect((await api.hello(a)).statusCode).toBe(429);
    expect((await api.hello(b)).statusCode).toBe(200);
  });

  it("fails closed during a store outage", async () => {
    const { api, store } = setup();
    const key = (await api.tenant()).apiKey;
    store.up = false;
    const r = await api.hello(key);
    expect(r.statusCode).toBe(503);
    expect(r.json().type).toMatch(/\/limiter-unavailable$/);
  });
});
