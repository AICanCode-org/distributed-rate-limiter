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
        // TODO(phase-3 M2): capacity = limit, refilled continuously at limit tokens per window_ms; a new
        //   bucket starts full. Refill by elapsed ms (never past limit), allow when tokens >= cost and
        //   subtract, else retry = ceil((cost - tokens) * window / limit). remaining = floor(tokens), reset =
        //   ceil((limit - tokens) * window / limit). See ADR-0001 and docs/phase-3.
        throw new UnsupportedOperationException("TODO(phase-3 M2): Algorithms.tokenBucket");
    }

    /**
     * Sliding window counter: estimate = prev * (overlap of the previous window) + cur. Everything is multiplied by
     * windowMs so the maths stays in integers (no rounding drift).
     */
    public static Result<WindowState> slidingWindow(WindowState state, long limit, long windowMs, long cost, long now) {
        // TODO(phase-3 M2): sliding window COUNTER: idx = now // window, elapsed = now - idx * window; roll
        //   the state (same window, next window: prev = cur, older: both 0, clock went backwards: keep the
        //   stored window). estimate = prev * (window - elapsed) + cur * window (integers!); allow when
        //   estimate + cost * window <= limit * window. Compute retry and reset exactly as the tests describe.
        throw new UnsupportedOperationException("TODO(phase-3 M2): Algorithms.slidingWindow");
    }
}
