// M1: API keys and the admin token. Keys are shown once and stored only as a SHA-256 hash.
import { randomBytes } from "node:crypto";

export const API_KEY_PREFIX = "rg_";
export const API_KEY_RE = /^rg_[0-9a-f]{32}$/;

/** 'rg_' + 32 lowercase hex characters (128 random bits from a CSPRNG). */
export function newApiKey(): string {
  // TODO(phase-3 M1): 'rg_' + 32 lowercase hex characters from a CSPRNG (128 random bits). Never Math.random
  //   / random.random.
  throw new Error("TODO(phase-3 M1): newApiKey");
}

/** Hex SHA-256. A fast hash is fine here: the key is 128 random bits, not a guessable password. */
export function hashApiKey(apiKey: string): string {
  // TODO(phase-3 M1): hex SHA-256 of the key (UTF-8). Only this hash is stored; a fast hash is fine for 128
  //   random bits.
  throw new Error("TODO(phase-3 M1): hashApiKey");
}

/** The first 9 characters ('rg_' + 6 hex): enough to recognise a key in a list, useless to an attacker. */
export function keyPrefix(apiKey: string): string {
  // TODO(phase-3 M1): the first 9 characters ('rg_' + 6 hex): shown in lists so humans can tell keys apart.
  throw new Error("TODO(phase-3 M1): keyPrefix");
}

export function looksLikeApiKey(value: string | undefined | null): value is string {
  // TODO(phase-3 M1): true only for 'rg_' + exactly 32 lowercase hex characters (malformed keys never reach
  //   Redis).
  throw new Error("TODO(phase-3 M1): looksLikeApiKey");
}

/** Compare secrets without leaking, through timing, how many leading characters match. */
export function constantTimeEquals(a: string, b: string): boolean {
  // TODO(phase-3 M1): compare two secrets in constant time (hmac.compare_digest / crypto.timingSafeEqual on
  //   equal-length buffers / MessageDigest.isEqual), never ==.
  throw new Error("TODO(phase-3 M1): constantTimeEquals");
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
    // TODO(phase-3 M1): the Authorization header must be 'Bearer <token>' and the token must equal
    //   ADMIN_TOKEN (constant time); anything else -> 401 unauthorized (the HTTP layer adds WWW-Authenticate:
    //   Bearer).
    throw new Error("TODO(phase-3 M1): AdminAuth.require");
  }
}
