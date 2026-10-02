package org.aicancode.rateguard.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.aicancode.rateguard.domain.Models.Decision;
import org.aicancode.rateguard.limiter.Algorithms.BucketState;
import org.aicancode.rateguard.limiter.Algorithms.Result;
import org.junit.jupiter.api.Test;

/** M2: token bucket as a pure function. Time is just a number here: no sleeping, no flakiness. */
class TokenBucketTest {

    private static final long T0 = 1_790_933_400_000L; // 2026-10-02T09:30:00Z in ms

    private static Result<BucketState> tb(BucketState state, long limit, long windowMs, long cost, long now) {
        return Algorithms.tokenBucket(state, limit, windowMs, cost, now);
    }

    private static List<Decision> run(long limit, long windowMs, long cost, long... times) {
        BucketState state = null;
        List<Decision> out = new ArrayList<>();
        for (long t : times) {
            Result<BucketState> r = tb(state, limit, windowMs, cost, t);
            state = r.state();
            out.add(r.decision());
        }
        return out;
    }

    private static BucketState after(long limit, long windowMs, long... times) {
        BucketState state = null;
        for (long t : times) {
            state = tb(state, limit, windowMs, 1, t).state();
        }
        return state;
    }

    @Test
    void startsFull() {
        Result<BucketState> r = tb(null, 5, 60_000, 1, T0);
        assertThat(r.decision()).isEqualTo(new Decision(true, 4, 0, 12_000));
        assertThat(r.state()).isEqualTo(new BucketState(4, T0));
    }

    @Test
    void burstsUpToTheCapacityThenDenies() {
        List<Decision> out = run(5, 60_000, 1, T0, T0, T0, T0, T0, T0);
        assertThat(out).extracting(Decision::allowed).containsExactly(true, true, true, true, true, false);
        assertThat(out).extracting(Decision::remaining).containsExactly(4L, 3L, 2L, 1L, 0L, 0L);
    }

    @Test
    void saysWhenOneTokenIsBack() {
        List<Decision> out = run(5, 60_000, 1, T0, T0, T0, T0, T0, T0);
        assertThat(out.getLast()).isEqualTo(new Decision(false, 0, 12_000, 60_000)); // 60 s / 5 tokens = every 12 s
    }

    @Test
    void refillsContinuously() {
        BucketState state = after(5, 60_000, T0, T0, T0, T0, T0);
        Result<BucketState> r = tb(state, 5, 60_000, 1, T0 + 11_999);
        assertThat(r.decision().allowed()).isFalse();
        assertThat(r.decision().retryAfterMs()).isEqualTo(1);
        Result<BucketState> next = tb(r.state(), 5, 60_000, 1, T0 + 12_000);
        assertThat(next.decision().allowed()).isTrue();
        assertThat(next.decision().remaining()).isZero();
    }

    @Test
    void neverRefillsAboveTheCapacity() {
        Result<BucketState> r = tb(after(5, 60_000, T0), 5, 60_000, 1, T0 + 3_600_000);
        assertThat(r.decision().remaining()).isEqualTo(4);
        assertThat(r.state().tokens()).isEqualTo(4);
    }

    @Test
    void spendsSeveralTokensForACost() {
        Result<BucketState> first = tb(null, 10, 10_000, 4, T0);
        assertThat(first.decision()).isEqualTo(new Decision(true, 6, 0, 4_000));
        Result<BucketState> second = tb(first.state(), 10, 10_000, 7, T0);
        assertThat(second.decision()).isEqualTo(new Decision(false, 6, 1_000, 4_000)); // a denial spends nothing
    }

    @Test
    void keepsFractionalTokensBetweenCalls() {
        BucketState state = tb(null, 3, 1_000, 3, T0).state(); // empty; refill 3 per second
        Result<BucketState> r = tb(state, 3, 1_000, 1, T0 + 200); // 0.6 tokens
        assertThat(r.decision().allowed()).isFalse();
        assertThat(r.decision().retryAfterMs()).isEqualTo(134); // ceil(0.4 * 1000 / 3)
        assertThat(tb(r.state(), 3, 1_000, 1, T0 + 334).decision().allowed()).isTrue();
    }

    @Test
    void neverCreatesTokensWhenTimeGoesBackwards() {
        Result<BucketState> r = tb(after(2, 60_000, T0, T0), 2, 60_000, 1, T0 - 30_000);
        assertThat(r.decision().allowed()).isFalse();
        assertThat(r.state().ts()).isEqualTo(T0);
        assertThat(tb(r.state(), 2, 60_000, 1, T0 + 30_000).decision().allowed()).isTrue();
    }

    @Test
    void resetsWhenTheBucketIsFullAgain() {
        assertThat(run(4, 8_000, 1, T0, T0).getLast().resetAfterMs()).isEqualTo(4_000);
    }

    @Test
    void handlesASlowPolicyOfOnePerHour() {
        Result<BucketState> first = tb(null, 1, 3_600_000, 1, T0);
        assertThat(first.decision()).isEqualTo(new Decision(true, 0, 0, 3_600_000));
        assertThat(tb(first.state(), 1, 3_600_000, 1, T0 + 60_000).decision())
                .isEqualTo(new Decision(false, 0, 3_540_000, 3_540_000));
    }
}
