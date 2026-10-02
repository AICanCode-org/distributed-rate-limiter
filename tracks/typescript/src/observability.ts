// Request IDs (v1.1): reuse a caller's X-Request-ID only if it is safe to echo; otherwise mint a UUID.
import { randomUUID } from "node:crypto";

export const REQUEST_ID_HEADER = "x-request-id";
const SAFE_ID = /^[A-Za-z0-9._-]{1,64}$/;

export function incomingOrNewId(value: unknown): string {
  return typeof value === "string" && SAFE_ID.test(value) ? value : randomUUID();
}
