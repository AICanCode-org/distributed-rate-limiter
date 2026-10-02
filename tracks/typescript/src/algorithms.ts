// M2: the two limiter algorithms as pure functions (state in, state + decision out; no I/O, no clock).
//
// spec/lua/token_bucket.lua and spec/lua/sliding_window.lua do exactly the same maths inside Redis.
// The in-memory store (unit tests, and the degraded mode in v1.1) calls these functions, and the
// integration tests check that both give the same answers step by step (ADR-0001).
import type { Decision } from "./models.js";

export interface BucketState {
  tokens: number;
  /** ms of the last refill */
  ts: number;
}

export interface WindowState {
  /** index of the current fixed window (floor(now / windowMs)) */
  w: number;
  /** cost units counted in the current window */
  cur: number;
  /** cost units counted in the previous window */
  prev: number;
}

/** Capacity `limit`, refilled continuously at `limit` tokens per `windowMs`. */
export function tokenBucket(
  state: BucketState | null,
  limit: number,
  windowMs: number,
  cost: number,
  now: number,
): [BucketState, Decision] {
  // TODO(phase-3 M2): capacity = limit, refilled continuously at limit tokens per window_ms; a new bucket
  //   starts full. Refill by elapsed ms (never past limit), allow when tokens >= cost and subtract, else retry
  //   = ceil((cost - tokens) * window / limit). remaining = floor(tokens), reset = ceil((limit - tokens) *
  //   window / limit). See ADR-0001 and docs/phase-3.
  throw new Error("TODO(phase-3 M2): tokenBucket");
}

/**
 * Sliding window counter: estimate = prev * (overlap of the previous window) + cur.
 * Everything is multiplied by windowMs so the maths stays in integers (no rounding drift).
 */
export function slidingWindow(
  state: WindowState | null,
  limit: number,
  windowMs: number,
  cost: number,
  now: number,
): [WindowState, Decision] {
  // TODO(phase-3 M2): sliding window COUNTER: idx = now // window, elapsed = now - idx * window; roll the
  //   state (same window, next window: prev = cur, older: both 0, clock went backwards: keep the stored
  //   window). estimate = prev * (window - elapsed) + cur * window (integers!); allow when estimate + cost *
  //   window <= limit * window. Compute retry and reset exactly as the tests describe.
  throw new Error("TODO(phase-3 M2): slidingWindow");
}
