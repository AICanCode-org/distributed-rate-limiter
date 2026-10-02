// M1: input validation. Every message is identical in all three tracks (clients and tests rely on them).
import { validationError } from "./errors.js";
import { type Policy } from "./models.js";

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
export const isInt = (value: unknown): value is number =>
  typeof value === "number" && Number.isSafeInteger(value);

/** Tenant ids end up inside Redis keys, so only a small, safe alphabet is accepted (no ':', '{', '*'). */
export function tenantId(value: unknown): string {
  // TODO(phase-3 M1): a string (400 'id is required') matching TENANT_ID_RE: 3-32 characters of a-z, 0-9 and
  //   inner hyphens (400 'id must be 3-32 characters of a-z, 0-9 and inner hyphens'). Ids end up inside Redis
  //   keys, so ':', '{' and '*' must never get through.
  throw new Error("TODO(phase-3 M1): tenantId");
}

export function isTenantId(value: string): boolean {
  // TODO(phase-3 M1): true when the string matches TENANT_ID_RE (used to answer 404 for impossible ids
  //   without asking Redis).
  throw new Error("TODO(phase-3 M1): isTenantId");
}

export function tenantName(value: unknown): string {
  // TODO(phase-3 M1): a string (400 'name is required'), trimmed, 1-80 characters counted as code points (400
  //   'name must be 1-80 characters').
  throw new Error("TODO(phase-3 M1): tenantName");
}

export function policy(value: unknown): Policy {
  // TODO(phase-3 M1): an object (400 'policy is required'); algorithm TOKEN_BUCKET or SLIDING_WINDOW; limit
  //   an INTEGER 1..MAX_LIMIT (booleans and floats are not integers); windowSeconds an integer
  //   1..MAX_WINDOW_SECONDS. Messages: see the validation tests.
  throw new Error("TODO(phase-3 M1): policy");
}

export function resource(value: unknown): string {
  // TODO(phase-3 M1): missing/null -> DEFAULT_RESOURCE ('default'); otherwise a string matching RESOURCE_RE
  //   (1-64 of a-z 0-9 . _ : -), else 400.
  throw new Error("TODO(phase-3 M1): resource");
}

export function cost(value: unknown): number {
  // TODO(phase-3 M1): missing/null -> 1; otherwise an integer 1..MAX_COST (1000), else 400 'cost must be an
  //   integer between 1 and 1000'.
  throw new Error("TODO(phase-3 M1): cost");
}

/** A query string: missing -> 60; ASCII digits only, 1..120. */
export function minutes(value: string | undefined): number {
  // TODO(phase-3 M1): a query string: missing -> DEFAULT_MINUTES (60); ASCII digits only and 1..MAX_MINUTES
  //   (120), else 400 'minutes must be an integer between 1 and 120'.
  throw new Error("TODO(phase-3 M1): minutes");
}
