// v1.1 (Phase 7): what RateGuard does while Redis is unavailable (ADR-0006: failMode).
import { describe, expect, it } from "vitest";
import { AdvisorService, RuleAdvisor } from "../../src/advisor.js";
import { Metrics } from "../../src/metrics.js";
import { InMemoryStore } from "../../src/store.js";
import { Api, FakeClock, makeApp, testSettings } from "../helpers.js";

function setup() {
  const clock = new FakeClock();
  const store = new InMemoryStore(clock.fn);
  const metrics = new Metrics();
  const breakerClock = { t: 1_000_000 };
  const app = makeApp(store, clock, new AdvisorService(new RuleAdvisor(), null, () => {}), testSettings(), {
    metrics,
    breakerNow: () => breakerClock.t,
  });
  return { store, metrics, breakerClock, app, api: new Api(app) };
}

const health = async (api: Api) => (await api.app.inject({ method: "GET", url: "/health" })).json();

describe("resilience (failMode)", () => {
  it("is CLOSED by default and answers 503", async () => {
    const { api, store, metrics } = setup();
    const key = (await api.tenant()).apiKey;
    expect((await api.hello(key)).statusCode).toBe(200);
    store.up = false;
    const r = await api.hello(key);
    expect(r.statusCode).toBe(503);
    expect(r.json().type).toMatch(/\/limiter-unavailable$/);
    expect(r.headers["retry-after"]).toBe("1");
    expect(metrics.value("rateguard_degraded_decisions_total")).toBe(0);
  });

  it("allows with OPEN and marks the answer degraded", async () => {
    const { api, store, metrics } = setup();
    const key = (await api.tenant("acme", "TOKEN_BUCKET", 5, 60, "Acme Corp", "OPEN")).apiKey;
    await api.hello(key); // Redis has seen the tenant once: its policy is now known locally
    store.up = false;
    for (let i = 0; i < 10; i++) {
      const r = await api.check(key, { cost: 2 });
      expect(r.statusCode).toBe(200);
      expect([r.json().allowed, r.json().degraded, r.json().remaining]).toEqual([true, true, 3]);
      expect(r.headers["x-ratelimit-degraded"]).toBe("true");
    }
    expect(metrics.value("rateguard_degraded_decisions_total")).toBe(10);
  });

  it("keeps limiting on this instance with LOCAL", async () => {
    const { api, store, metrics } = setup();
    const key = (await api.tenant("acme", "TOKEN_BUCKET", 2, 60, "Acme Corp", "LOCAL")).apiKey;
    await api.hello(key);
    store.up = false;
    const codes = [];
    for (let i = 0; i < 3; i++) codes.push((await api.hello(key)).statusCode);
    expect(codes).toEqual([200, 200, 429]);
    const denied = await api.hello(key);
    expect(denied.headers["x-ratelimit-degraded"]).toBe("true");
    expect(denied.headers["retry-after"]).toBe("30");
    const first = (await api.check(key, { resource: "orders.create", cost: 2 })).json();
    expect([first.allowed, first.remaining, first.degraded]).toEqual([true, 0, true]);
    expect((await api.check(key, { resource: "orders.create" })).json().allowed).toBe(false);
    expect(metrics.value("rateguard_decisions_denied_total")).toBe(3);
  });

  it("answers 503, not allowed, for an unknown key during an outage", async () => {
    const { api, store } = setup();
    const key = (await api.tenant("acme", "TOKEN_BUCKET", 5, 60, "Acme Corp", "OPEN")).apiKey; // never used: not cached
    store.up = false;
    expect((await api.hello(key)).statusCode).toBe(503);
    expect((await api.hello("rg_" + "f".repeat(32))).statusCode).toBe(503);
  });

  it("stops calling Redis once the breaker opens, and recovers", async () => {
    const { api, store, metrics, breakerClock } = setup();
    const key = (await api.tenant("acme", "TOKEN_BUCKET", 5, 60, "Acme Corp", "OPEN")).apiKey;
    await api.hello(key);
    store.up = false;
    for (let i = 0; i < 6; i++) expect((await api.hello(key)).statusCode).toBe(200);
    // 3 real failures open the circuit; the other calls are skipped (no timeout to wait for)
    expect(metrics.value("rateguard_store_errors_total")).toBe(3);
    expect(metrics.value("rateguard_store_short_circuits_total")).toBeGreaterThanOrEqual(3);
    expect((await health(api)).status).toBe("DEGRADED");
    store.up = true;
    expect((await health(api)).status).toBe("DEGRADED"); // still open: no trial yet
    breakerClock.t += 5000; // REDIS_BREAKER_COOLDOWN_MS
    expect(await health(api)).toEqual({ status: "UP", checks: { redis: "UP" } });
    const r = await api.check(key);
    expect(r.json().degraded).toBe(false);
    expect(r.headers["x-ratelimit-degraded"]).toBeUndefined();
  });

  it("does not open the breaker on a duplicate id", async () => {
    const { api, metrics } = setup();
    await api.tenant();
    const body = {
      id: "acme",
      name: "Again",
      policy: { algorithm: "TOKEN_BUCKET", limit: 1, windowSeconds: 1 },
    };
    for (let i = 0; i < 4; i++)
      expect((await api.admin("POST", "/api/v1/admin/tenants", body)).statusCode).toBe(409);
    expect((await health(api)).status).toBe("UP");
    expect(metrics.value("rateguard_store_errors_total")).toBe(0);
  });

  it("round-trips and validates failMode", async () => {
    const { api } = setup();
    expect(
      ((await api.tenant("acme", "TOKEN_BUCKET", 5, 60, "Acme Corp", "LOCAL")).policy as { failMode: string })
        .failMode,
    ).toBe("LOCAL");
    const policy = { algorithm: "TOKEN_BUCKET", limit: 5, windowSeconds: 60 };
    const r = await api.admin("PUT", "/api/v1/admin/tenants/acme/policy", { ...policy, failMode: "OPEN" });
    expect([r.statusCode, r.json().policy.failMode]).toEqual([200, "OPEN"]);
    const bad = await api.admin("PUT", "/api/v1/admin/tenants/acme/policy", { ...policy, failMode: "MAYBE" });
    expect(bad.statusCode).toBe(400);
    expect(bad.json().detail).toBe("policy.failMode must be CLOSED, OPEN or LOCAL");
  });

  it("keeps the failMode in the suggested policy", async () => {
    const { api } = setup();
    const key = (await api.tenant("acme", "TOKEN_BUCKET", 2, 3600, "Acme Corp", "LOCAL")).apiKey;
    for (let i = 0; i < 6; i++) await api.hello(key);
    const body = (await api.admin("POST", "/api/v1/admin/tenants/acme/advice")).json();
    expect(body.suggestedPolicy.failMode).toBe("LOCAL");
  });
});
