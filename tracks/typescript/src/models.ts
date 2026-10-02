// Plain data. No framework imports, no I/O.
export const TOKEN_BUCKET = "TOKEN_BUCKET";
export const SLIDING_WINDOW = "SLIDING_WINDOW";
export const ALGORITHMS = [TOKEN_BUCKET, SLIDING_WINDOW] as const;
export type Algorithm = (typeof ALGORITHMS)[number];

export interface Policy {
  algorithm: Algorithm;
  limit: number;
  windowSeconds: number;
}

export const windowMs = (p: Policy) => p.windowSeconds * 1000;

export interface Tenant {
  id: string;
  name: string;
  policy: Policy;
  keyHash: string;
  keyPrefix: string;
  createdAt: Date;
  updatedAt: Date;
}

/** The answer of one limiter call (identical fields to the Lua scripts' return value). */
export interface Decision {
  allowed: boolean;
  remaining: number;
  retryAfterMs: number;
  resetAfterMs: number;
}

/** A decision plus what it was about (what the HTTP layer renders). */
export interface Verdict {
  tenant: Tenant;
  resource: string;
  cost: number;
  decision: Decision;
}

export interface UsagePoint {
  /** epoch minute (ms / 60000, rounded down) */
  minute: number;
  allowed: number;
  denied: number;
}
