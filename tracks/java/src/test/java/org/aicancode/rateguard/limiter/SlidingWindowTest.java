package org.aicancode.rateguard.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.aicancode.rateguard.domain.Models.Decision;
import org.aicancode.rateguard.limiter.Algorithms.Result;
import org.aicancode.rateguard.limiter.Algorithms.WindowState;
import org.junit.jupiter.api.Test;

/** M2: sliding window counter as a pure function (integer maths, scaled by the window). */
class SlidingWindowTest {

    private static final long W = 60_000;
    private static final long T0 = 29_848_884L * W; // the start of a window (2026-10-02T09:24:00Z)
    private static final long I = T0 / W;

    private static Result<WindowState> sw(WindowState state, long limit, long cost, long now) {
        return Algorithms.slidingWindow(state, limit, W, cost, now);
    }

    private static WindowState ws(long w, long cur, long prev) {
        return new WindowState(w, cur, prev);
    }

    @Test
    void countsWithinOneWindow() {
        WindowState state = null;
        List<Decision> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Result<WindowState> r = sw(state, 3, 1, T0 + 1_000);
            state = r.state();
            out.add(r.decision());
        }
        assertThat(out).extracting(Decision::allowed).containsExactly(true, true, true, false);
        assertThat(out).extracting(Decision::remaining).containsExactly(2L, 1L, 0L, 0L);
        assertThat(state).isEqualTo(ws(I, 3, 0));
    }

    @Test
    void countsThePreviousWindowByItsOverlap() {
        WindowState state = sw(ws(I - 1, 10, 0), 10, 1, T0 + 15_000).state(); // prev=10, 25 % slid out
        assertThat(state).isEqualTo(ws(I, 1, 10));
        Result<WindowState> r = sw(state, 10, 1, T0 + 15_000);
        assertThat(r.decision().allowed()).isTrue();
        assertThat(r.decision().remaining()).isZero();
        assertThat(sw(r.state(), 10, 1, T0 + 15_000).decision().allowed()).isFalse();
    }

    @Test
    void retriesOnceThePreviousWindowSlidOutFurther() {
        assertThat(sw(ws(I, 2, 10), 10, 1, T0 + 30_000).decision().allowed()).isTrue();
        // needs 10 * (1 - f) + 6 + 1 <= 10  ->  f >= 0.7  ->  at 42 000 ms: 12 s from now
        assertThat(sw(ws(I, 6, 10), 10, 1, T0 + 30_000).decision()).isEqualTo(new Decision(false, 0, 12_000, 2 * W - 30_000));
    }

    @Test
    void retriesInTheNextWindowWhenOnlyThatOneCanFitIt() {
        // next window: 3 * (1 - f) + 1 <= 3  ->  f >= 1/3  ->  15 s left here + 20 s into the next
        assertThat(sw(ws(I, 3, 0), 3, 1, T0 + 45_000).decision()).isEqualTo(new Decision(false, 0, 35_000, 75_000));
    }

    @Test
    void rollsTheWindowForward() {
        Result<WindowState> r = sw(ws(I - 1, 4, 9), 5, 1, T0 + 59_999);
        assertThat(r.state()).isEqualTo(ws(I, 1, 4));
        assertThat(r.decision().allowed()).isTrue();
    }

    @Test
    void forgetsEverythingAfterALongPause() {
        Result<WindowState> r = sw(ws(I - 5, 5, 5), 5, 1, T0);
        assertThat(r.state()).isEqualTo(ws(I, 1, 0));
        assertThat(r.decision()).isEqualTo(new Decision(true, 4, 0, 2 * W));
    }

    @Test
    void staysInTheStoredWindowWhenTimeGoesBackwards() {
        Result<WindowState> r = sw(ws(I + 1, 2, 0), 3, 1, T0 + 10);
        assertThat(r.state()).isEqualTo(ws(I + 1, 3, 0));
        assertThat(r.decision().allowed()).isTrue();
    }

    @Test
    void countsACostAsSeveralUnits() {
        Result<WindowState> first = sw(null, 10, 4, T0);
        assertThat(first.decision()).isEqualTo(new Decision(true, 6, 0, 2 * W));
        assertThat(first.state().cur()).isEqualTo(4);
        Result<WindowState> second = sw(first.state(), 10, 7, T0);
        assertThat(second.decision().allowed()).isFalse();
        assertThat(second.decision().remaining()).isEqualTo(6);
    }

    @Test
    void resetsDependingOnWhatIsStillCounted() {
        Result<WindowState> r = sw(ws(I - 1, 2, 0), 5, 5, T0 + 10_000);
        assertThat(r.decision().allowed()).isFalse();
        assertThat(r.decision().resetAfterMs()).isEqualTo(W - 10_000);
    }

    @Test
    void needsAnEmptyWindowForACostEqualToTheLimit() {
        // next window: 1 * (1 - f) + 3 <= 3 -> f = 1 -> 40 s here + 60 s
        assertThat(sw(ws(I, 1, 0), 3, 3, T0 + 20_000).decision()).isEqualTo(new Decision(false, 2, 100_000, 100_000));
    }
}
