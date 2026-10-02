// M5: POST /admin/tenants/{id}/advice over HTTP. Advice is returned, never applied.
import { expect, it } from "vitest";
import { AdvisorService, LlmAdvisor, RuleAdvisor } from "../../src/advisor.js";
import { InMemoryStore } from "../../src/store.js";
import { Api, FakeClock, makeApp } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("api-advice");

function setup(advisor?: AdvisorService) {
  const clock = new FakeClock();
  const store = new InMemoryStore(clock.fn);
  const app = makeApp(store, clock, advisor);
  return { app, api: new Api(app) };
}

describe("advice API", () => {
  it("advises offline for a busy tenant", async () => {
    const { api } = setup();
    const key = (await api.tenant("acme", "TOKEN_BUCKET", 2, 3600)).apiKey;
    for (let i = 0; i < 5; i++) await api.check(key);
    const r = await api.admin("POST", "/api/v1/admin/tenants/acme/advice?minutes=10");
    expect(r.statusCode).toBe(200);
    const body = r.json();
    expect([body.tenantId, body.provider, body.fallback]).toEqual(["acme", "rules", false]);
    expect(body.currentPolicy).toEqual({
      algorithm: "TOKEN_BUCKET",
      limit: 2,
      windowSeconds: 3600,
      failMode: "CLOSED",
    });
    expect([body.stats.allowed, body.stats.denied, body.stats.minutes]).toEqual([2, 3, 10]);
    expect(body.action).toBe("RAISE_LIMIT");
    expect(body.suggestedPolicy).toEqual({
      algorithm: "TOKEN_BUCKET",
      limit: 20,
      windowSeconds: 3600,
      failMode: "CLOSED",
    });
    expect(body.summary.startsWith("60% of requests were denied (3 of 5).")).toBe(true);
    expect(body.generatedAt).toBe("2026-10-02T09:30:00.000Z");
    // nothing was applied
    expect((await api.admin("GET", "/api/v1/admin/tenants/acme")).json().policy.limit).toBe(2);
  });

  it("has no suggested policy for KEEP", async () => {
    const { api } = setup();
    await api.tenant();
    const body = (await api.admin("POST", "/api/v1/admin/tenants/acme/advice")).json();
    expect([body.action, body.suggestedPolicy, body.stats.minutes]).toEqual(["KEEP", null, 60]);
  });

  it("falls back when the LLM is down", async () => {
    const down = { chat: async () => Promise.reject(new Error("LLM timed out")) };
    const { api } = setup(new AdvisorService(new RuleAdvisor(), new LlmAdvisor(down), () => {}));
    await api.tenant();
    const r = await api.admin("POST", "/api/v1/admin/tenants/acme/advice");
    expect(r.statusCode).toBe(200);
    expect([r.json().provider, r.json().fallback]).toEqual(["rules", true]);
  });

  it("reports advice errors", async () => {
    const { app, api } = setup();
    expect((await app.inject({ method: "POST", url: "/api/v1/admin/tenants/acme/advice" })).statusCode).toBe(
      401,
    );
    expect((await api.admin("POST", "/api/v1/admin/tenants/ghost/advice")).statusCode).toBe(404);
    await api.tenant();
    expect((await api.admin("POST", "/api/v1/admin/tenants/acme/advice?minutes=121")).statusCode).toBe(400);
  });
});
