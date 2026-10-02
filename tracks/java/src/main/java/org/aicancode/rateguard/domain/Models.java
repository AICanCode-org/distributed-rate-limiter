package org.aicancode.rateguard.domain;

import java.time.Instant;

/** Plain data. No framework imports, no I/O. */
public final class Models {

    private Models() {
    }

    public enum Algorithm { TOKEN_BUCKET, SLIDING_WINDOW }

    /** v1.1 (ADR-0006): what to do while Redis is unavailable. */
    public enum FailMode { CLOSED, OPEN, LOCAL }

    public record Policy(Algorithm algorithm, int limit, int windowSeconds, FailMode failMode) {
        /** A v1.0-style policy: CLOSED, the v1.0 behaviour. */
        public Policy(Algorithm algorithm, int limit, int windowSeconds) {
            this(algorithm, limit, windowSeconds, FailMode.CLOSED);
        }

        public long windowMs() {
            return windowSeconds * 1000L;
        }

        public Policy withLimit(int newLimit) {
            return new Policy(algorithm, newLimit, windowSeconds, failMode);
        }
    }

    public record Tenant(String id, String name, Policy policy, String keyHash, String keyPrefix, Instant createdAt,
            Instant updatedAt) {
    }

    /** The answer of one limiter call (identical fields to the Lua scripts' return value). */
    public record Decision(boolean allowed, long remaining, long retryAfterMs, long resetAfterMs, boolean degraded) {
        public Decision(boolean allowed, long remaining, long retryAfterMs, long resetAfterMs) {
            this(allowed, remaining, retryAfterMs, resetAfterMs, false);
        }

        /** v1.1: the same answer, marked as decided by the failMode because Redis was unavailable. */
        public Decision asDegraded() {
            return new Decision(allowed, remaining, retryAfterMs, resetAfterMs, true);
        }
    }

    /** A decision plus what it was about (what the HTTP layer renders). */
    public record Verdict(Tenant tenant, String resource, int cost, Decision decision) {
    }

    /** One minute of usage. {@code minute} is the epoch minute (ms / 60000, rounded down). */
    public record UsagePoint(long minute, long allowed, long denied) {
    }
}
