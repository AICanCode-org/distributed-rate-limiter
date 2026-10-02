// M1: API keys and the admin token. Keys are shown once and stored only as a SHA-256 hash.
import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import { unauthorized } from "./errors.js";

export const API_KEY_PREFIX = "rg_";
export const API_KEY_RE = /^rg_[0-9a-f]{32}$/;

/** 'rg_' + 32 lowercase hex characters (128 random bits from a CSPRNG). */
export const newApiKey = (): string => API_KEY_PREFIX + randomBytes(16).toString("hex");

/** Hex SHA-256. A fast hash is fine here: the key is 128 random bits, not a guessable password. */
export const hashApiKey = (apiKey: string): string =>
  createHash("sha256").update(apiKey, "utf8").digest("hex");

/** The first 9 characters ('rg_' + 6 hex): enough to recognise a key in a list, useless to an attacker. */
export const keyPrefix = (apiKey: string): string => apiKey.slice(0, 9);

export const looksLikeApiKey = (value: string | undefined | null): value is string =>
  typeof value === "string" && API_KEY_RE.test(value);

/** Compare secrets without leaking, through timing, how many leading characters match. */
export function constantTimeEquals(a: string, b: string): boolean {
  const x = Buffer.from(a, "utf8");
  const y = Buffer.from(b, "utf8");
  // timingSafeEqual throws on different lengths, so check that first (the length is not a secret).
  return x.length === y.length && timingSafeEqual(x, y);
}

export function resolveSecret(
  value: string | null,
  warn: (msg: string) => void = console.warn,
  name = "ADMIN_TOKEN",
): string {
  if (value) return value;
  warn(`${name} is not set: using a random value for this process (dev only)`);
  return randomBytes(32).toString("base64url");
}

/** The admin API is protected by one shared bearer token (ADMIN_TOKEN). */
export class AdminAuth {
  constructor(private readonly token: string) {}

  require(authorization: string | undefined): void {
    if (authorization === undefined || !authorization.startsWith("Bearer ")) throw unauthorized();
    if (!constantTimeEquals(authorization.slice("Bearer ".length).trim(), this.token)) throw unauthorized();
  }
}
