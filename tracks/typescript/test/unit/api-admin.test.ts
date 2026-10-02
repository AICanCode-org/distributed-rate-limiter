// M4: the admin API over HTTP (tenants, policies, keys, usage).
import { expect, it } from "vitest";
import { InMemoryStore } from "../../src/store.js";
import { ADMIN, Api, FakeClock, makeApp } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("api-admin");
const KEY1 = "rg_00000000000000000000000000000001";

function setup() {
  const clock = new FakeClock();
  const store = new InMemoryStore(clock.fn);
  const app = makeApp(store, clock);
  return { clock, store, app, api: new Api(app) };
}

describe("admin API", () => {
  it("creates a tenant with 201, Location and the key once", async () => {
    const { app } = setup();
    const r = await app.inject({
      method: "POST",
      url: "/api/v1/admin/tenants",
      headers: ADMIN,
      payload: {
        id: "acme",
        name: " Acme Corp ",
        policy: { algorithm: "TOKEN_BUCKET", limit: 5, windowSeconds: 60 },
      },
    });
    expect(r.statusCode).toBe(201);
    expect(r.headers.location).toBe("/api/v1/admin/tenants/acme");
    expect(r.json()).toEqual({
      id: "acme",
      name: "Acme Corp",
      policy: { algorithm: "TOKEN_BUCKET", limit: 5, windowSeconds: 60 },
      keyPrefix: "rg_000000",
      createdAt: "2026-10-02T09:30:00.000Z",
      updatedAt: "2026-10-02T09:30:00.000Z",
      apiKey: KEY1,
    });
    const got = await app.inject({ method: "GET", url: "/api/v1/admin/tenants/acme", headers: ADMIN });
    expect(got.statusCode).toBe(200);
    expect(got.json()).not.toHaveProperty("apiKey");
    expect(got.body).not.toContain(KEY1);
  });

  it("needs the admin token", async () => {
    const { app } = setup();
    for (const headers of [
      {},
      { authorization: "Bearer wrong" },
      { authorization: "test-admin-token-at-least-32-characters" },
    ]) {
      const r = await app.inject({ method: "GET", url: "/api/v1/admin/tenants", headers });
      expect(r.statusCode).toBe(401);
      expect(r.headers["www-authenticate"]).toBe("Bearer");
      expect(r.headers["content-type"]).toContain("application/problem+json");
      expect(r.json().type).toMatch(/\/unauthorized$/);
    }
  });

  it("does not accept a tenant API key as the admin token", async () => {
    const { app, api } = setup();
    const { apiKey } = await api.tenant();
    const r = await app.inject({
      method: "GET",
      url: "/api/v1/admin/tenants",
      headers: { authorization: `Bearer ${apiKey}` },
    });
    expect(r.statusCode).toBe(401);
  });

  it("lists tenants sorted by id", async () => {
    const { api } = setup();
    await api.tenant("zeta");
    await api.tenant("beta");
    const r = await api.admin("GET", "/api/v1/admin/tenants");
    expect(r.json().items.map((t: { id: string }) => t.id)).toEqual(["beta", "zeta"]);
  });

  it("answers validation errors and conflicts with problems", async () => {
    const { app, api } = setup();
    let r = await api.admin("POST", "/api/v1/admin/tenants", { id: "acme", name: "A", policy: {} });
    expect(r.statusCode).toBe(400);
    expect(r.json().detail).toBe("policy.algorithm must be TOKEN_BUCKET or SLIDING_WINDOW");
    r = await app.inject({
      method: "POST",
      url: "/api/v1/admin/tenants",
      headers: { ...ADMIN, "content-type": "application/json" },
      payload: "{nope",
    });
    expect(r.statusCode).toBe(400);
    expect(r.json().detail).toBe("request body must be a JSON object");
    await api.tenant();
    r = await api.admin("POST", "/api/v1/admin/tenants", {
      id: "acme",
      name: "Again",
      policy: { algorithm: "TOKEN_BUCKET", limit: 1, windowSeconds: 1 },
    });
    expect(r.statusCode).toBe(409);
    expect(r.json().type).toMatch(/\/tenant-exists$/);
  });

  it("answers an unknown tenant with 404", async () => {
    const { api } = setup();
    const cases: ["GET" | "POST" | "DELETE", string][] = [
      ["GET", ""],
      ["DELETE", ""],
      ["POST", "/reset"],
      ["POST", "/rotate-key"],
      ["GET", "/usage"],
    ];
    for (const [method, path] of cases) {
      const r = await api.admin(method, `/api/v1/admin/tenants/ghost${path}`);
      expect(r.statusCode, `${method} ${path}`).toBe(404);
      expect(r.json().detail).toBe("tenant not found");
    }
  });

  it("replaces the policy with PUT", async () => {
    const { api, clock } = setup();
    await api.tenant();
    clock.advance({ minutes: 1 });
    const r = await api.admin("PUT", "/api/v1/admin/tenants/acme/policy", {
      algorithm: "SLIDING_WINDOW",
      limit: 100,
      windowSeconds: 3600,
    });
    expect(r.statusCode).toBe(200);
    expect(r.json().policy).toEqual({ algorithm: "SLIDING_WINDOW", limit: 100, windowSeconds: 3600 });
    expect(r.json().updatedAt).toBe("2026-10-02T09:31:00.000Z");
    const bad = await api.admin("PUT", "/api/v1/admin/tenants/acme/policy", { algorithm: "TOKEN_BUCKET" });
    expect(bad.statusCode).toBe(400);
  });

  it("rotates the key", async () => {
    const { api } = setup();
    const old = (await api.tenant()).apiKey;
    const r = await api.admin("POST", "/api/v1/admin/tenants/acme/rotate-key");
    expect(r.statusCode).toBe(200);
    expect(r.json().apiKey).not.toBe(old);
    expect(r.json().keyPrefix).toBe("rg_000000");
    expect((await api.check(old)).statusCode).toBe(401);
    expect((await api.check(r.json().apiKey)).statusCode).toBe(200);
  });

  it("gives a full bucket back on reset", async () => {
    const { api } = setup();
    const key = (await api.tenant("acme", "TOKEN_BUCKET", 1)).apiKey;
    expect((await api.check(key)).json().allowed).toBe(true);
    expect((await api.check(key)).json().allowed).toBe(false);
    expect((await api.admin("POST", "/api/v1/admin/tenants/acme/reset")).statusCode).toBe(204);
    expect((await api.check(key)).json().allowed).toBe(true);
  });

  it("revokes the key when the tenant is deleted", async () => {
    const { api } = setup();
    const key = (await api.tenant()).apiKey;
    expect((await api.admin("DELETE", "/api/v1/admin/tenants/acme")).statusCode).toBe(204);
    expect((await api.admin("GET", "/api/v1/admin/tenants/acme")).statusCode).toBe(404);
    expect((await api.check(key)).statusCode).toBe(401);
  });

  it("returns the usage series", async () => {
    const { api, clock } = setup();
    const key = (await api.tenant("acme", "TOKEN_BUCKET", 2, 3600)).apiKey;
    for (let i = 0; i < 3; i++) await api.check(key);
    clock.advance({ minutes: 1 });
    await api.check(key);
    const r = await api.admin("GET", "/api/v1/admin/tenants/acme/usage?minutes=3");
    expect(r.statusCode).toBe(200);
    expect(r.json()).toEqual({
      tenantId: "acme",
      minutes: 3,
      totals: { allowed: 2, denied: 2 },
      series: [
        { minute: "2026-10-02T09:29:00.000Z", allowed: 0, denied: 0 },
        { minute: "2026-10-02T09:30:00.000Z", allowed: 2, denied: 1 },
        { minute: "2026-10-02T09:31:00.000Z", allowed: 0, denied: 1 },
      ],
    });
    expect((await api.admin("GET", "/api/v1/admin/tenants/acme/usage?minutes=0")).statusCode).toBe(400);
  });

  it("answers a store outage on the admin API with 503", async () => {
    const { api, store } = setup();
    await api.tenant();
    store.up = false;
    const r = await api.admin("GET", "/api/v1/admin/tenants");
    expect(r.statusCode).toBe(503);
    expect(r.headers["retry-after"]).toBe("1");
    expect(r.json().type).toMatch(/\/limiter-unavailable$/);
  });
});
