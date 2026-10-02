package org.aicancode.rateguard.domain;

import java.time.Instant;

/** Plain data. No framework imports, no I/O. */
public final class Models {

    private Models() {
    }

    public enum Algorithm { TOKEN_BUCKET, SLIDING_WINDOW }

    public record Policy(Algorithm algorithm, int limit, int windowSeconds) {
        public long windowMs() {
            return windowSeconds * 1000L;
        }

        public Policy withLimit(int newLimit) {
            return new Policy(algorithm, newLimit, windowSeconds);
        }
    }

    public record Tenant(String id, String name, Policy policy, String keyHash, String keyPrefix, Instant createdAt,
            Instant updatedAt) {
    }

    /** The answer of one limiter call (identical fields to the Lua scripts' return value). */
    public record Decision(boolean allowed, long remaining, long retryAfterMs, long resetAfterMs) {
    }

    /** A decision plus what it was about (what the HTTP layer renders). */
    public record Verdict(Tenant tenant, String resource, int cost, Decision decision) {
    }

    /** One minute of usage. {@code minute} is the epoch minute (ms / 60000, rounded down). */
    public record UsagePoint(long minute, long allowed, long denied) {
    }
}
