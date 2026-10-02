// M1: input validation. The messages are part of the contract (identical in every track).
import { expect, it } from "vitest";
import * as v from "../../src/validation.js";
import { messageOf } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("validation");

describe("validation", () => {
  it("needs an object body", () => {
    expect(v.requireObject({ a: 1 })).toEqual({ a: 1 });
    expect(messageOf(() => v.requireObject(["a"]))).toBe("request body must be a JSON object");
    expect(messageOf(() => v.requireObject(null))).toBe("request body must be a JSON object");
  });

  it("accepts tenant ids from the safe alphabet", () => {
    for (const good of ["acme", "a1b", "big-co-2", "x".repeat(32)]) expect(v.tenantId(good)).toBe(good);
  });

  it("rejects tenant ids that could break a Redis key", () => {
    for (const bad of [
      "ab",
      "x".repeat(33),
      "Acme",
      "-acme",
      "acme-",
      "ac:me",
      "ac}me",
      "ac*me",
      "ac me",
      "äcme",
    ]) {
      expect(messageOf(() => v.tenantId(bad))).toBe(
        "id must be 3-32 characters of a-z, 0-9 and inner hyphens",
      );
    }
  });

  it("requires a tenant id", () => {
    expect(messageOf(() => v.tenantId(undefined))).toBe("id is required");
    expect(messageOf(() => v.tenantId(42))).toBe("id is required");
    expect(v.isTenantId("acme")).toBe(true);
    expect(v.isTenantId("ACME")).toBe(false);
  });

  it("trims and limits the name", () => {
    expect(v.tenantName("  Acme Corp ")).toBe("Acme Corp");
    expect(v.tenantName("x".repeat(80))).toBe("x".repeat(80));
    expect(messageOf(() => v.tenantName(null))).toBe("name is required");
    expect(messageOf(() => v.tenantName("   "))).toBe("name must be 1-80 characters");
    expect(messageOf(() => v.tenantName("x".repeat(81)))).toBe("name must be 1-80 characters");
  });

  it("parses a valid policy", () => {
    expect(v.policy({ algorithm: "SLIDING_WINDOW", limit: 100, windowSeconds: 60 })).toEqual({
      algorithm: "SLIDING_WINDOW",
      limit: 100,
      windowSeconds: 60,
    });
    expect(v.policy({ algorithm: "TOKEN_BUCKET", limit: 100_000, windowSeconds: 86_400 }).limit).toBe(
      100_000,
    );
    expect(v.policy({ algorithm: "TOKEN_BUCKET", limit: 1, windowSeconds: 1 }).windowSeconds).toBe(1);
  });

  it("requires a policy object", () => {
    expect(messageOf(() => v.policy(null))).toBe("policy is required");
    expect(messageOf(() => v.policy("TOKEN_BUCKET"))).toBe("policy is required");
  });

  it("checks the algorithm", () => {
    for (const algorithm of [undefined, "token_bucket", "LEAKY_BUCKET", 1]) {
      expect(messageOf(() => v.policy({ algorithm, limit: 1, windowSeconds: 1 }))).toBe(
        "policy.algorithm must be TOKEN_BUCKET or SLIDING_WINDOW",
      );
    }
  });

  it("needs a real integer limit in range", () => {
    for (const limit of [undefined, 0, 100_001, 1.5, "10", true]) {
      expect(messageOf(() => v.policy({ algorithm: "TOKEN_BUCKET", limit, windowSeconds: 60 }))).toBe(
        "policy.limit must be an integer between 1 and 100000",
      );
    }
  });

  it("needs a real integer window in range", () => {
    for (const windowSeconds of [undefined, 0, 86_401, 2.5, "60", false]) {
      expect(messageOf(() => v.policy({ algorithm: "TOKEN_BUCKET", limit: 10, windowSeconds }))).toBe(
        "policy.windowSeconds must be an integer between 1 and 86400",
      );
    }
  });

  it("defaults the resource and checks its alphabet", () => {
    expect(v.resource(undefined)).toBe("default");
    expect(v.resource("orders.create:v2_x-1")).toBe("orders.create:v2_x-1");
    for (const bad of ["", "Orders", "a*b", "a{b}", "x".repeat(65), 7, "a b"]) {
      expect(messageOf(() => v.resource(bad))).toBe(
        "resource must be 1-64 characters of a-z, 0-9, '.', '_', ':' and '-'",
      );
    }
  });

  it("defaults the cost to one and bounds it", () => {
    expect(v.cost(undefined)).toBe(1);
    expect(v.cost(1000)).toBe(1000);
    for (const bad of [0, 1001, 2.5, "3", true]) {
      expect(messageOf(() => v.cost(bad))).toBe("cost must be an integer between 1 and 1000");
    }
  });

  it("reads the minutes query parameter", () => {
    expect(v.minutes(undefined)).toBe(60);
    expect(v.minutes("1")).toBe(1);
    expect(v.minutes("120")).toBe(120);
    for (const bad of ["0", "121", "-5", "1.5", "", "abc", "١٢"]) {
      expect(messageOf(() => v.minutes(bad))).toBe("minutes must be an integer between 1 and 120");
    }
  });
});
