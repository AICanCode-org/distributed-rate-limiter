// M4: the limiter service: checks, costs, headers and the fail-closed rule (ADR-0004).
import { expect, it } from "vitest";
import { ApiError } from "../../src/errors.js";
import { LimiterService, rateLimitHeaders, secondsUp } from "../../src/limiter.js";
import type { Algorithm, Tenant } from "../../src/models.js";
import { InMemoryStore } from "../../src/store.js";
import { FakeClock } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("limiter");
const NOW = new Date("2026-10-02T09:30:00.000Z");
const tenant = (limit = 3, windowSeconds = 60, algorithm: Algorithm = "TOKEN_BUCKET"): Tenant => ({
  id: "acme",
  name: "Acme Corp",
  policy: { algorithm, limit, windowSeconds },
  keyHash: "h",
  keyPrefix: "rg_000000",
  createdAt: NOW,
  updatedAt: NOW,
});

function setup() {
  const store = new InMemoryStore(new FakeClock().fn);
  return { store, limiter: new LimiterService(store) };
}

async function apiError(promise: Promise<unknown>): Promise<ApiError> {
  const err = await promise.then(
    () => null,
    (e: unknown) => e,
  );
  expect(err).toBeInstanceOf(ApiError);
  return err as ApiError;
}

describe("limiter service", () => {
  it("defaults to resource 'default' and cost one", async () => {
    const v = await setup().limiter.check(tenant(), undefined);
    expect([v.resource, v.cost, v.decision.allowed, v.decision.remaining]).toEqual(["default", 1, true, 2]);
  });

  it("reads resource and cost", async () => {
    const { limiter } = setup();
    const v = await limiter.check(tenant(), { resource: "orders.create", cost: 3 });
    expect(v.resource).toBe("orders.create");
    expect(v.decision.remaining).toBe(0);
    expect((await limiter.check(tenant(), { resource: "orders.create" })).decision.allowed).toBe(false);
    expect((await limiter.check(tenant(), { resource: "search" })).decision.allowed).toBe(true);
  });

  it("validates the body", async () => {
    const { limiter } = setup();
    for (const body of [["x"], { cost: 0 }, { resource: "A" }]) {
      expect((await apiError(limiter.check(tenant(), body))).status).toBe(400);
    }
  });

  it("answers a cost above the limit with 422 because it can never pass", async () => {
    const e = await apiError(setup().limiter.check(tenant(3), { cost: 4 }));
    expect([e.status, e.slug]).toEqual([422, "cost-exceeds-limit"]);
    expect(e.detail).toBe("cost 4 is more than the limit of 3");
  });

  it("enforces with a 429 and Retry-After", async () => {
    const { limiter } = setup();
    for (let i = 0; i < 3; i++) await limiter.enforce(tenant());
    const e = await apiError(limiter.enforce(tenant()));
    expect([e.status, e.slug]).toEqual([429, "rate-limited"]);
    expect(e.detail).toBe("limit of 3 per 60 s reached, retry in 20 s");
    expect(e.headers).toEqual({
      "X-RateLimit-Limit": "3",
      "X-RateLimit-Remaining": "0",
      "X-RateLimit-Reset": "60",
      "Retry-After": "20",
    });
  });

  it("never sends a Retry-After below one second", async () => {
    const { limiter } = setup();
    const t = tenant(1000, 1);
    for (let i = 0; i < 1000; i++) await limiter.enforce(t);
    const e = await apiError(limiter.enforce(t));
    expect(e.headers["Retry-After"]).toBe("1"); // 1 ms rounds UP to 1 s, never 0
  });

  it("fails closed with 503 during a store outage", async () => {
    const { limiter, store } = setup();
    store.up = false;
    const e = await apiError(limiter.check(tenant(), undefined));
    expect([e.status, e.slug]).toEqual([503, "limiter-unavailable"]);
    expect(e.headers).toEqual({ "Retry-After": "1" });
  });

  it("rounds milliseconds up", () => {
    expect([0, 1, 1000, 1001].map(secondsUp)).toEqual([0, 1, 1, 2]);
  });

  it("builds the rate-limit headers", async () => {
    const v = await setup().limiter.check(tenant(10, 60), { cost: 4 });
    expect(rateLimitHeaders(v)).toEqual({
      "X-RateLimit-Limit": "10",
      "X-RateLimit-Remaining": "6",
      "X-RateLimit-Reset": "24",
    });
  });
});
