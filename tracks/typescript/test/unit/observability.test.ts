// v1.1 (Phase 7): request IDs, /metrics and the LLM circuit breaker.
import { describe, expect, it } from "vitest";
import { COUNTERS, Metrics } from "../../src/metrics.js";
import { incomingOrNewId } from "../../src/observability.js";
import { InMemoryStore } from "../../src/store.js";
import { buildAdvisor } from "../../src/wiring.js";
import { Api, FakeClock, makeApp, testSettings } from "../helpers.js";

const metric = async (api: Api, name: string) => {
  const text = (await api.app.inject({ method: "GET", url: "/metrics" })).body;
  const line = text.split("\n").find((l) => l.startsWith(name + " "))!;
  return Number(line.split(" ")[1]);
};

function setup() {
  const clock = new FakeClock();
  const app = makeApp(new InMemoryStore(clock.fn), clock);
  return { app, api: new Api(app) };
}

describe("request IDs", () => {
  it("echoes a safe X-Request-ID and mints a missing one", async () => {
    const { app } = setup();
    const echoed = await app.inject({
      method: "GET",
      url: "/health",
      headers: { "x-request-id": "abc-123" },
    });
    expect(echoed.headers["x-request-id"]).toBe("abc-123");
    const minted = String((await app.inject({ method: "GET", url: "/health" })).headers["x-request-id"]);
    expect(minted).toMatch(/^[0-9a-f-]{36}$/);
  });

  it.each(["", "has space", "x".repeat(65), "evil\u00e9", "a;b"])("replaces the unsafe id %j", (value) => {
    expect(incomingOrNewId(value)).not.toBe(value);
  });

  it("puts the request ID on errors too", async () => {
    const { app } = setup();
    const r = await app.inject({
      method: "GET",
      url: "/api/v1/nope",
      headers: { "x-request-id": "trace-404" },
    });
    expect([r.statusCode, r.headers["x-request-id"]]).toEqual([404, "trace-404"]);
  });
});

describe("/metrics", () => {
  it("lists every counter", async () => {
    const { app } = setup();
    const r = await app.inject({ method: "GET", url: "/metrics" });
    expect(r.statusCode).toBe(200);
    expect(r.headers["content-type"]).toMatch(/^text\/plain/);
    for (const name of Object.keys(COUNTERS)) {
      expect(r.body).toContain(`# TYPE ${name} counter`);
      expect(r.body).toContain(`\n${name} 0`);
    }
  });

  it("counts tenants and decisions", async () => {
    const { api } = setup();
    const key = (await api.tenant("acme", "TOKEN_BUCKET", 2)).apiKey;
    await api.tenant("beta");
    for (let i = 0; i < 3; i++) await api.hello(key);
    await api.check(key); // another resource: its own bucket
    expect(await metric(api, "rateguard_tenants_created_total")).toBe(2);
    expect(await metric(api, "rateguard_decisions_allowed_total")).toBe(3);
    expect(await metric(api, "rateguard_decisions_denied_total")).toBe(1);
    expect(await metric(api, "rateguard_degraded_decisions_total")).toBe(0);
  });

  it("renders the text format", () => {
    const m = new Metrics();
    m.inc("rateguard_store_errors_total", 3);
    expect(m.value("rateguard_store_errors_total")).toBe(3);
    expect(m.render()).toContain("rateguard_store_errors_total 3\n");
    expect(m.render().endsWith("\n")).toBe(true);
  });
});

describe("the LLM behind its breaker", () => {
  it("short-circuits an unreachable LLM in the real wiring", async () => {
    const metrics = new Metrics();
    const settings = testSettings({
      aiProvider: "llm",
      llmBaseUrl: "http://127.0.0.1:9/v1",
      llmTimeoutMs: 500,
    });
    const clock = new FakeClock();
    const app = makeApp(
      new InMemoryStore(clock.fn),
      clock,
      buildAdvisor(settings, () => {}, metrics),
      settings,
      {
        metrics,
      },
    );
    const api = new Api(app);
    await api.hello((await api.tenant()).apiKey);
    for (let i = 0; i < 4; i++) {
      const r = await api.admin("POST", "/api/v1/admin/tenants/acme/advice");
      expect([r.statusCode, r.json().fallback]).toEqual([200, true]);
    }
    expect(metrics.value("rateguard_llm_short_circuits_total")).toBe(1);
    expect(metrics.value("rateguard_ai_fallback_total")).toBe(4);
  });
});
