// M1: API keys are random, shown once, stored as a hash; the admin token is compared in constant time.
import { createHash } from "node:crypto";
import { expect, it } from "vitest";
import { ApiError } from "../../src/errors.js";
import {
  AdminAuth,
  constantTimeEquals,
  hashApiKey,
  keyPrefix,
  looksLikeApiKey,
  newApiKey,
  resolveSecret,
} from "../../src/security.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("security");

describe("security", () => {
  it("creates unique keys with the documented shape", () => {
    const keys = new Set(Array.from({ length: 200 }, newApiKey));
    expect(keys.size).toBe(200);
    for (const k of keys) expect(looksLikeApiKey(k)).toBe(true);
  });

  it("hashes the whole key with hex SHA-256", () => {
    const key = "rg_" + "ab".repeat(16);
    expect(hashApiKey(key)).toBe(createHash("sha256").update(key).digest("hex"));
    expect(hashApiKey(key)).toHaveLength(64);
  });

  it("keeps a short, recognisable prefix", () => {
    expect(keyPrefix("rg_0123456789abcdef0123456789abcdef")).toBe("rg_012345");
  });

  it("rejects malformed keys", () => {
    for (const bad of [
      undefined,
      null,
      "",
      "rg_123",
      "RG_" + "a".repeat(32),
      "rg_" + "A".repeat(32),
      "rg_" + "a".repeat(33),
    ]) {
      expect(looksLikeApiKey(bad)).toBe(false);
    }
  });

  it("compares in constant time", () => {
    expect(constantTimeEquals("same-secret", "same-secret")).toBe(true);
    expect(constantTimeEquals("same-secret", "same-secreT")).toBe(false);
    expect(constantTimeEquals("short", "a-much-longer-value")).toBe(false);
  });

  it("accepts only the exact bearer token", () => {
    const auth = new AdminAuth("the-admin-token-0123456789-abcdefghij");
    auth.require("Bearer the-admin-token-0123456789-abcdefghij");
    for (const bad of [
      undefined,
      "",
      "the-admin-token-0123456789-abcdefghij",
      "Basic abc",
      "Bearer wrong",
      "Bearer ",
    ]) {
      try {
        auth.require(bad);
        throw new Error("expected 401");
      } catch (err) {
        expect(err).toBeInstanceOf(ApiError);
        expect((err as ApiError).status).toBe(401);
        expect((err as ApiError).headers).toEqual({ "WWW-Authenticate": "Bearer" });
      }
    }
  });

  it("keeps a configured secret and invents one otherwise", () => {
    expect(resolveSecret("configured", () => {})).toBe("configured");
    const a = resolveSecret(null, () => {});
    expect(a).not.toBe(resolveSecret(null, () => {}));
    expect(a.length).toBeGreaterThanOrEqual(32);
  });
});
