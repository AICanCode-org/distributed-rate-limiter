// Always on: the app boots and reports the store's health.
import { describe, expect, it } from "vitest";
import { InMemoryStore } from "../../src/store.js";
import { makeApp } from "../helpers.js";

describe("health", () => {
  it("is UP with the store", async () => {
    const r = await makeApp(new InMemoryStore()).inject({ method: "GET", url: "/health" });
    expect(r.statusCode).toBe(200);
    expect(r.json()).toEqual({ status: "UP", checks: { redis: "UP" } });
  });

  it("is 503 when the store is down", async () => {
    const store = new InMemoryStore();
    store.up = false;
    const r = await makeApp(store).inject({ method: "GET", url: "/health" });
    expect(r.statusCode).toBe(503);
    expect(r.json().checks.redis).toBe("DOWN");
  });

  it("answers unknown routes with problem+json", async () => {
    const r = await makeApp(new InMemoryStore()).inject({ method: "GET", url: "/nope" });
    expect(r.statusCode).toBe(404);
    expect(r.headers["content-type"]).toContain("application/problem+json");
    expect(r.json().type).toBe("https://rateguard.example/problems/not-found");
  });
});
