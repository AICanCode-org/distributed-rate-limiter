// M4: the tenant service (framework-free business logic behind the admin API).
import { expect, it } from "vitest";
import { hashApiKey } from "../../src/security.js";
import { InMemoryStore } from "../../src/store.js";
import { TenantService } from "../../src/tenants.js";
import { FakeClock, keyGen, statusOf } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("tenants");
const BODY = {
  id: "acme",
  name: "Acme Corp",
  policy: { algorithm: "TOKEN_BUCKET", limit: 5, windowSeconds: 60 },
};

function setup() {
  const clock = new FakeClock();
  const store = new InMemoryStore(clock.fn);
  return { clock, store, svc: new TenantService(store, clock.fn, keyGen()) };
}

describe("tenant service", () => {
  it("returns the key once and stores only its hash", async () => {
    const { svc, store } = setup();
    const [tenant, key] = await svc.create(BODY);
    expect(key).toBe("rg_00000000000000000000000000000001");
    expect(tenant.keyHash).toBe(hashApiKey(key));
    expect(tenant.keyPrefix).toBe("rg_000000");
    expect(tenant.policy).toEqual({
      algorithm: "TOKEN_BUCKET",
      limit: 5,
      windowSeconds: 60,
      failMode: "CLOSED",
    });
    expect(JSON.stringify(await store.listTenants())).not.toContain(key);
  });

  it("validates everything on create", async () => {
    const { svc } = setup();
    expect(await statusOf(svc.create(["x"]))).toEqual([400, "validation-error"]);
    expect(await statusOf(svc.create({ ...BODY, id: "A" }))).toEqual([400, "validation-error"]);
    expect(await statusOf(svc.create({ ...BODY, policy: null }))).toEqual([400, "validation-error"]);
  });

  it("answers a duplicate id with 409", async () => {
    const { svc } = setup();
    await svc.create(BODY);
    expect(await statusOf(svc.create(BODY))).toEqual([409, "tenant-exists"]);
  });

  it("answers unknown or impossible ids with 404", async () => {
    const { svc } = setup();
    expect(await statusOf(svc.get("nobody"))).toEqual([404, "not-found"]);
    expect(await statusOf(svc.get("NOT A VALID ID"))).toEqual([404, "not-found"]);
  });

  it("replaces the policy and bumps updatedAt", async () => {
    const { svc, clock } = setup();
    await svc.create(BODY);
    clock.advance({ minutes: 5 });
    const t = await svc.setPolicy("acme", { algorithm: "SLIDING_WINDOW", limit: 50, windowSeconds: 10 });
    expect(t.policy).toEqual({
      algorithm: "SLIDING_WINDOW",
      limit: 50,
      windowSeconds: 10,
      failMode: "CLOSED",
    });
    expect(t.updatedAt.getTime()).toBeGreaterThan(t.createdAt.getTime());
    expect((await svc.get("acme")).policy.limit).toBe(50);
  });

  it("invalidates the old key on rotation", async () => {
    const { svc } = setup();
    const [, old] = await svc.create(BODY);
    const [, fresh] = await svc.rotateKey("acme");
    expect(fresh).not.toBe(old);
    expect((await svc.authenticate(fresh)).id).toBe("acme");
    expect(await statusOf(svc.authenticate(old))).toEqual([401, "invalid-api-key"]);
  });

  it("rejects missing, malformed and unknown keys", async () => {
    const { svc, store } = setup();
    await svc.create(BODY);
    store.up = false; // malformed keys must not even reach the store
    for (const bad of [undefined, "", "Bearer x", "rg_short"]) {
      expect(await statusOf(svc.authenticate(bad))).toEqual([401, "invalid-api-key"]);
    }
    store.up = true;
    expect(await statusOf(svc.authenticate("rg_" + "f".repeat(32)))).toEqual([401, "invalid-api-key"]);
  });

  it("deletes and resets", async () => {
    const { svc, store } = setup();
    await svc.create(BODY);
    await store.check(
      "acme",
      "x",
      { algorithm: "TOKEN_BUCKET", limit: 5, windowSeconds: 60, failMode: "CLOSED" },
      5,
    );
    expect(await svc.reset("acme")).toBe(1);
    await svc.delete("acme");
    expect(await statusOf(svc.get("acme"))).toEqual([404, "not-found"]);
    expect(await statusOf(svc.reset("acme"))).toEqual([404, "not-found"]);
  });

  it("validates the usage minutes", async () => {
    const { svc } = setup();
    await svc.create(BODY);
    const [tenant, minutes, points] = await svc.usage("acme", undefined);
    expect([tenant.id, minutes, points.length]).toEqual(["acme", 60, 60]);
    expect(await statusOf(svc.usage("acme", "500"))).toEqual([400, "validation-error"]);
  });
});
