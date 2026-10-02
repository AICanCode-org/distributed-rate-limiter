"""M2: the two limiter algorithms as pure functions (state in, state + decision out; no I/O, no clock).

spec/lua/token_bucket.lua and spec/lua/sliding_window.lua do exactly the same maths inside Redis.
The in-memory store (unit tests, and the degraded mode in v1.1) calls these functions, and the
integration tests check that both give the same answers step by step (ADR-0001).
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from .models import Decision


@dataclass(frozen=True)
class BucketState:
    tokens: float
    ts: int  # ms of the last refill


@dataclass(frozen=True)
class WindowState:
    w: int  # index of the current fixed window (now_ms // window_ms)
    cur: int  # cost units counted in the current window
    prev: int  # cost units counted in the previous window


def token_bucket(
    state: BucketState | None, limit: int, window_ms: int, cost: int, now: int
) -> tuple[BucketState, Decision]:
    """Capacity `limit`, refilled continuously at `limit` tokens per `window_ms`."""
    if state is None:
        tokens, ts = float(limit), now  # a new bucket starts full
    else:
        tokens, ts = state.tokens, state.ts
    if now > ts:
        tokens = min(float(limit), tokens + (now - ts) * limit / window_ms)
        ts = now
    allowed = tokens >= cost
    retry = 0
    if allowed:
        tokens -= cost
    else:
        retry = math.ceil((cost - tokens) * window_ms / limit)
    reset = math.ceil((limit - tokens) * window_ms / limit)
    return BucketState(tokens, ts), Decision(allowed, math.floor(tokens), retry, reset)


def sliding_window(
    state: WindowState | None, limit: int, window_ms: int, cost: int, now: int
) -> tuple[WindowState, Decision]:
    """Sliding window counter: estimate = prev * (overlap of the previous window) + cur.

    Everything is multiplied by window_ms so the maths stays in integers (no rounding drift).
    """
    idx = now // window_ms
    elapsed = now - idx * window_ms
    if state is None:
        cur = prev = 0
    elif idx < state.w:  # the clock went backwards: stay in the stored window
        idx, elapsed, cur, prev = state.w, 0, state.cur, state.prev
    elif idx == state.w:
        cur, prev = state.cur, state.prev
    elif idx == state.w + 1:
        cur, prev = 0, state.cur
    else:
        cur = prev = 0
    weighted = prev * (window_ms - elapsed) + cur * window_ms
    retry = 0
    allowed = weighted + cost * window_ms <= limit * window_ms
    if allowed:
        cur += cost
        weighted += cost * window_ms
    elif cur + cost <= limit:
        # fits in this window once enough of the previous window has slid out
        retry = window_ms - (limit - cost - cur) * window_ms // prev - elapsed
    else:
        # only fits in the next window, once enough of this one has slid out
        retry = (window_ms - elapsed) + window_ms - (limit - cost) * window_ms // cur
    remaining = max(0, (limit * window_ms - weighted) // window_ms)
    if cur > 0:
        reset = 2 * window_ms - elapsed
    elif prev > 0:
        reset = window_ms - elapsed
    else:
        reset = 0
    return WindowState(idx, cur, prev), Decision(allowed, remaining, retry, reset)
