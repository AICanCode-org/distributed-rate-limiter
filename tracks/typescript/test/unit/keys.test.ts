// M3: the Redis key layout (spec/architecture.md, ADR-0003).
import { expect, it } from "vitest";
import * as keys from "../../src/keys.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("keys");
const glob = (pattern: string) =>
  new RegExp("^" + pattern.replace(/[.+?^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*") + "$");

describe("keys", () => {
  it("names tenants and API keys", () => {
    expect(keys.TENANTS).toBe("rg:tenants");
    expect(keys.tenantKey("acme")).toBe("rg:tenant:acme");
    expect(keys.apiKeyKey("ab12")).toBe("rg:key:ab12");
  });

  it("puts the tenant hash tag in every limiter key", () => {
    expect(keys.limiterKey("acme", "TOKEN_BUCKET", "orders.create")).toBe("rg:{acme}:tb:orders.create");
    expect(keys.limiterKey("acme", "SLIDING_WINDOW", "default")).toBe("rg:{acme}:sw:default");
    expect(keys.usageKey("acme")).toBe("rg:{acme}:usage");
  });

  it("scans one tenant only", () => {
    const re = glob(keys.tenantPattern("acme"));
    expect(re.test("rg:{acme}:tb:x")).toBe(true);
    expect(re.test("rg:{acme-2}:tb:x")).toBe(false);
    expect(re.test("rg:tenant:acme")).toBe(false);
  });

  it("maps minutes to ring slots", () => {
    expect(keys.USAGE_SLOTS).toBe(180);
    expect(keys.usageSlot(29_848_884)).toBe(29_848_884 % 180);
    expect(keys.usageSlot(180)).toBe(0);
  });
});
