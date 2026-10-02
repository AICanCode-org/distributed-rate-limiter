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
  let tokens = state === null ? limit : state.tokens; // a new bucket starts full
  let ts = state === null ? now : state.ts;
  if (now > ts) {
    tokens = Math.min(limit, tokens + ((now - ts) * limit) / windowMs);
    ts = now;
  }
  const allowed = tokens >= cost;
  let retry = 0;
  if (allowed) tokens -= cost;
  else retry = Math.ceil(((cost - tokens) * windowMs) / limit);
  const reset = Math.ceil(((limit - tokens) * windowMs) / limit);
  return [
    { tokens, ts },
    { allowed, remaining: Math.floor(tokens), retryAfterMs: retry, resetAfterMs: reset },
  ];
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
  let idx = Math.floor(now / windowMs);
  let elapsed = now - idx * windowMs;
  let cur = 0;
  let prev = 0;
  if (state === null) {
    // a new window pair
  } else if (idx < state.w) {
    // the clock went backwards: stay in the stored window
    [idx, elapsed, cur, prev] = [state.w, 0, state.cur, state.prev];
  } else if (idx === state.w) {
    [cur, prev] = [state.cur, state.prev];
  } else if (idx === state.w + 1) {
    prev = state.cur;
  }
  let weighted = prev * (windowMs - elapsed) + cur * windowMs;
  let retry = 0;
  const allowed = weighted + cost * windowMs <= limit * windowMs;
  if (allowed) {
    cur += cost;
    weighted += cost * windowMs;
  } else if (cur + cost <= limit) {
    // fits in this window once enough of the previous window has slid out
    retry = windowMs - Math.floor(((limit - cost - cur) * windowMs) / prev) - elapsed;
  } else {
    // only fits in the next window, once enough of this one has slid out
    retry = windowMs - elapsed + windowMs - Math.floor(((limit - cost) * windowMs) / cur);
  }
  const remaining = Math.max(0, Math.floor((limit * windowMs - weighted) / windowMs));
  const reset = cur > 0 ? 2 * windowMs - elapsed : prev > 0 ? windowMs - elapsed : 0;
  return [
    { w: idx, cur, prev },
    { allowed, remaining, retryAfterMs: retry, resetAfterMs: reset },
  ];
}
