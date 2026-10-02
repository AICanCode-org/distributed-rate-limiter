"""M2: the two limiter algorithms as pure functions (state in, state + decision out; no I/O, no clock).

spec/lua/token_bucket.lua and spec/lua/sliding_window.lua do exactly the same maths inside Redis.
The in-memory store (unit tests, and the degraded mode in v1.1) calls these functions, and the
integration tests check that both give the same answers step by step (ADR-0001).
"""

from __future__ import annotations

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
    # TODO(phase-3 M2): capacity = limit, refilled continuously at limit tokens per window_ms; a new
    #   bucket starts full. Refill by elapsed ms (never past limit), allow when tokens >= cost and
    #   subtract, else retry = ceil((cost - tokens) * window / limit). remaining = floor(tokens),
    #   reset = ceil((limit - tokens) * window / limit). See ADR-0001 and docs/phase-3.
    raise NotImplementedError("TODO(phase-3 M2): token_bucket")


def sliding_window(
    state: WindowState | None, limit: int, window_ms: int, cost: int, now: int
) -> tuple[WindowState, Decision]:
    """Sliding window counter: estimate = prev * (overlap of the previous window) + cur.

    Everything is multiplied by window_ms so the maths stays in integers (no rounding drift).
    """
    # TODO(phase-3 M2): sliding window COUNTER: idx = now // window, elapsed = now - idx * window;
    #   roll the state (same window, next window: prev = cur, older: both 0, clock went backwards:
    #   keep the stored window). estimate = prev * (window - elapsed) + cur * window (integers!);
    #   allow when estimate + cost * window <= limit * window. Compute retry and reset exactly as the
    #   tests describe.
    raise NotImplementedError("TODO(phase-3 M2): sliding_window")
