package org.aicancode.rateguard.limiter;

import org.aicancode.rateguard.domain.Models.Decision;

/**
 * M2: the two limiter algorithms as pure functions (state in, state + decision out; no I/O, no clock).
 *
 * <p>spec/lua/token_bucket.lua and spec/lua/sliding_window.lua do exactly the same maths inside Redis. The in-memory
 * store (unit tests, and the degraded mode in v1.1) calls these functions, and the integration tests check that both
 * give the same answers step by step (ADR-0001).
 */
public final class Algorithms {

    private Algorithms() {
    }

    /** Token bucket state: tokens (fractional) and the ms of the last refill. */
    public record BucketState(double tokens, long ts) {
    }

    /** Sliding window state: the index of the current fixed window and the units counted in it and the previous one. */
    public record WindowState(long w, long cur, long prev) {
    }

    public record Result<S>(S state, Decision decision) {
    }

    private static long ceil(double value) {
        return (long) Math.ceil(value);
    }

    /** Capacity {@code limit}, refilled continuously at {@code limit} tokens per {@code windowMs}. */
    public static Result<BucketState> tokenBucket(BucketState state, long limit, long windowMs, long cost, long now) {
        double tokens = state == null ? limit : state.tokens(); // a new bucket starts full
        long ts = state == null ? now : state.ts();
        if (now > ts) {
            tokens = Math.min(limit, tokens + (double) (now - ts) * limit / windowMs);
            ts = now;
        }
        boolean allowed = tokens >= cost;
        long retry = 0;
        if (allowed) {
            tokens -= cost;
        } else {
            retry = ceil((cost - tokens) * windowMs / limit);
        }
        long reset = ceil((limit - tokens) * windowMs / limit);
        return new Result<>(new BucketState(tokens, ts), new Decision(allowed, (long) Math.floor(tokens), retry, reset));
    }

    /**
     * Sliding window counter: estimate = prev * (overlap of the previous window) + cur. Everything is multiplied by
     * windowMs so the maths stays in integers (no rounding drift).
     */
    public static Result<WindowState> slidingWindow(WindowState state, long limit, long windowMs, long cost, long now) {
        long idx = Math.floorDiv(now, windowMs);
        long elapsed = now - idx * windowMs;
        long cur = 0;
        long prev = 0;
        if (state != null) {
            if (idx < state.w()) {
                // the clock went backwards: stay in the stored window
                idx = state.w();
                elapsed = 0;
                cur = state.cur();
                prev = state.prev();
            } else if (idx == state.w()) {
                cur = state.cur();
                prev = state.prev();
            } else if (idx == state.w() + 1) {
                prev = state.cur();
            }
        }
        long weighted = prev * (windowMs - elapsed) + cur * windowMs;
        long retry = 0;
        boolean allowed = weighted + cost * windowMs <= limit * windowMs;
        if (allowed) {
            cur += cost;
            weighted += cost * windowMs;
        } else if (cur + cost <= limit) {
            // fits in this window once enough of the previous window has slid out
            retry = windowMs - Math.floorDiv((limit - cost - cur) * windowMs, prev) - elapsed;
        } else {
            // only fits in the next window, once enough of this one has slid out
            retry = windowMs - elapsed + windowMs - Math.floorDiv((limit - cost) * windowMs, cur);
        }
        long remaining = Math.max(0, Math.floorDiv(limit * windowMs - weighted, windowMs));
        long reset = cur > 0 ? 2 * windowMs - elapsed : prev > 0 ? windowMs - elapsed : 0;
        return new Result<>(new WindowState(idx, cur, prev), new Decision(allowed, remaining, retry, reset));
    }
}
