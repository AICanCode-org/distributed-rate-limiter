// M1: input validation. Every message is identical in all three tracks (clients and tests rely on them).
import { validationError } from "./errors.js";
import { ALGORITHMS, CLOSED, FAIL_MODES, type Algorithm, type FailMode, type Policy } from "./models.js";

export const TENANT_ID_RE = /^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$/;
export const RESOURCE_RE = /^[a-z0-9._:-]{1,64}$/;
export const MAX_LIMIT = 100_000;
export const MAX_WINDOW_SECONDS = 86_400;
export const MAX_COST = 1_000;
export const MAX_NAME = 80;
export const DEFAULT_RESOURCE = "default";
export const DEFAULT_MINUTES = 60;
export const MAX_MINUTES = 120;

export function requireObject(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw validationError("request body must be a JSON object");
  }
  return value as Record<string, unknown>;
}

/** JSON numbers: 1.0 === 1 in JavaScript, so "integer" means a safe integer value. */
const isInt = (value: unknown): value is number => typeof value === "number" && Number.isSafeInteger(value);

/** Tenant ids end up inside Redis keys, so only a small, safe alphabet is accepted (no ':', '{', '*'). */
export function tenantId(value: unknown): string {
  if (typeof value !== "string") throw validationError("id is required");
  if (!TENANT_ID_RE.test(value))
    throw validationError("id must be 3-32 characters of a-z, 0-9 and inner hyphens");
  return value;
}

export const isTenantId = (value: string): boolean => TENANT_ID_RE.test(value);

export function tenantName(value: unknown): string {
  if (typeof value !== "string") throw validationError("name is required");
  const name = value.trim();
  const length = [...name].length;
  if (length < 1 || length > MAX_NAME) throw validationError("name must be 1-80 characters");
  return name;
}

export function policy(value: unknown): Policy {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw validationError("policy is required");
  }
  const body = value as Record<string, unknown>;
  const algorithm = body.algorithm;
  if (typeof algorithm !== "string" || !(ALGORITHMS as readonly string[]).includes(algorithm)) {
    throw validationError("policy.algorithm must be TOKEN_BUCKET or SLIDING_WINDOW");
  }
  const limit = body.limit;
  if (!isInt(limit) || limit < 1 || limit > MAX_LIMIT) {
    throw validationError("policy.limit must be an integer between 1 and 100000");
  }
  const windowSeconds = body.windowSeconds;
  if (!isInt(windowSeconds) || windowSeconds < 1 || windowSeconds > MAX_WINDOW_SECONDS) {
    throw validationError("policy.windowSeconds must be an integer between 1 and 86400");
  }
  // v1.1: optional, so v1.0 clients keep working (they get CLOSED, the v1.0 behaviour)
  const failMode = body.failMode === undefined ? CLOSED : body.failMode;
  if (typeof failMode !== "string" || !(FAIL_MODES as readonly string[]).includes(failMode)) {
    throw validationError("policy.failMode must be CLOSED, OPEN or LOCAL");
  }
  return { algorithm: algorithm as Algorithm, limit, windowSeconds, failMode: failMode as FailMode };
}

export function resource(value: unknown): string {
  if (value === undefined || value === null) return DEFAULT_RESOURCE;
  if (typeof value !== "string" || !RESOURCE_RE.test(value)) {
    throw validationError("resource must be 1-64 characters of a-z, 0-9, '.', '_', ':' and '-'");
  }
  return value;
}

export function cost(value: unknown): number {
  if (value === undefined || value === null) return 1;
  if (!isInt(value) || value < 1 || value > MAX_COST) {
    throw validationError("cost must be an integer between 1 and 1000");
  }
  return value;
}

/** A query string: missing -> 60; ASCII digits only, 1..120. */
export function minutes(value: string | undefined): number {
  if (value === undefined) return DEFAULT_MINUTES;
  if (!/^[0-9]+$/.test(value) || Number(value) < 1 || Number(value) > MAX_MINUTES) {
    throw validationError("minutes must be an integer between 1 and 120");
  }
  return Number(value);
}
