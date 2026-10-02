import { expect } from "vitest";
import type { FastifyInstance } from "fastify";
import { AdvisorService, RuleAdvisor } from "../src/advisor.js";
import { buildApp } from "../src/app.js";
import type { Clock } from "../src/clock.js";
import { loadSettings, type Settings } from "../src/config.js";
import { ApiError } from "../src/errors.js";
import type { Store } from "../src/store.js";
import { wire } from "../src/wiring.js";

export const ADMIN_TOKEN = "test-admin-token-at-least-32-characters";
export const ADMIN = { authorization: `Bearer ${ADMIN_TOKEN}` };

/** A clock you can move. Tests should never depend on the real time. */
export class FakeClock {
  now = new Date("2026-10-02T09:30:00.000Z");
  readonly fn: Clock = () => this.now;
  advance({ minutes = 0, seconds = 0, ms = 0 }) {
    this.now = new Date(this.now.getTime() + (minutes * 60 + seconds) * 1000 + ms);
  }
}

/** Predictable API keys: rg_000...001, rg_000...002, ... */
export function keyGen(): () => string {
  let n = 0;
  return () => `rg_${(++n).toString(16).padStart(32, "0")}`;
}

export function testSettings(overrides: Partial<Settings> = {}): Settings {
  return { ...loadSettings({}), adminToken: ADMIN_TOKEN, aiProvider: "offline", ...overrides };
}

/** The real Fastify app on the given store (no network unless the store is a RedisStore). */
export function makeApp(
  store: Store,
  clock: FakeClock | null = new FakeClock(),
  advisor: AdvisorService = new AdvisorService(new RuleAdvisor(), null, () => {}),
  settings: Settings = testSettings(),
): FastifyInstance {
  return buildApp(wire(settings, store, { clock: clock?.fn, advisor, newKey: keyGen(), warn: () => {} }));
}

/** Expects `promise` to reject with an ApiError and returns [status, slug]. */
export async function statusOf(promise: Promise<unknown>): Promise<[number, string]> {
  try {
    await promise;
  } catch (err) {
    expect(err).toBeInstanceOf(ApiError);
    return [(err as ApiError).status, (err as ApiError).slug];
  }
  throw new Error("expected an ApiError");
}

/** Expects `fn` to throw an ApiError (400) and returns its detail. */
export function messageOf(fn: () => unknown): string {
  try {
    fn();
  } catch (err) {
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(400);
    return (err as ApiError).detail ?? "";
  }
  throw new Error("expected a validation error");
}

/** Small helpers so API tests read like the user stories. */
export class Api {
  constructor(readonly app: FastifyInstance) {}

  async tenant(id = "acme", algorithm = "TOKEN_BUCKET", limit = 5, windowSeconds = 60, name = "Acme Corp") {
    const r = await this.app.inject({
      method: "POST",
      url: "/api/v1/admin/tenants",
      headers: ADMIN,
      payload: { id, name, policy: { algorithm, limit, windowSeconds } },
    });
    expect(r.statusCode, r.body).toBe(201);
    return r.json() as { apiKey: string; [k: string]: unknown };
  }

  check(apiKey: string, body?: Record<string, unknown>) {
    return this.app.inject({
      method: "POST",
      url: "/api/v1/limits/check",
      headers: { "x-api-key": apiKey },
      ...(body ? { payload: body } : {}),
    });
  }

  hello(apiKey: string) {
    return this.app.inject({ method: "GET", url: "/api/v1/demo/hello", headers: { "x-api-key": apiKey } });
  }

  admin(method: "GET" | "POST" | "PUT" | "DELETE", url: string, payload?: unknown) {
    return this.app.inject({
      method,
      url,
      headers: ADMIN,
      ...(payload === undefined ? {} : { payload: payload as object }),
    });
  }
}
