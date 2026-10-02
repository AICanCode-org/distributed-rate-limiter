"""M2: sliding window counter as a pure function (integer maths, scaled by the window)."""

from app.algorithms import WindowState, sliding_window
from app.models import Decision

W = 60_000
T0 = 29_848_884 * W  # the start of a window (2026-10-02T09:24:00Z)


def test_counts_within_one_window():
    state, out = None, []
    for _ in range(4):
        state, d = sliding_window(state, 3, W, 1, T0 + 1_000)
        out.append(d)
    assert [d.allowed for d in out] == [True, True, True, False]
    assert [d.remaining for d in out] == [2, 1, 0, 0]
    assert state == WindowState(T0 // W, 3, 0)


def test_previous_window_counts_by_overlap():
    state, _ = sliding_window(WindowState(T0 // W - 1, 10, 0), 10, W, 1, T0 + 15_000)  # prev=10, 25 % slid out
    # estimate = 10 * 0.75 + 0 = 7.5 -> 2 more fit (7.5 + 1 <= 10, 8.5 + 1 <= 10)
    assert state.prev == 10 and state.cur == 1
    state, d = sliding_window(state, 10, W, 1, T0 + 15_000)
    assert d.allowed and d.remaining == 0
    _, d = sliding_window(state, 10, W, 1, T0 + 15_000)
    assert not d.allowed


def test_retry_when_the_previous_window_must_slide_out_further():
    state = WindowState(T0 // W, 2, 10)  # cur=2, prev=10, limit 10
    state, d = sliding_window(state, 10, W, 1, T0 + 30_000)  # estimate 10*0.5 + 2 = 7 -> fits
    assert d.allowed
    state, d = sliding_window(WindowState(T0 // W, 6, 10), 10, W, 1, T0 + 30_000)  # 5 + 6 = 11 -> no
    # needs 10 * (1 - f) + 6 + 1 <= 10  ->  f >= 0.7  ->  at 42 000 ms: 12 s from now
    assert d == Decision(False, 0, 12_000, 2 * W - 30_000)


def test_retry_when_only_the_next_window_can_fit_it():
    state, d = sliding_window(WindowState(T0 // W, 3, 0), 3, W, 1, T0 + 45_000)
    # next window: 3 * (1 - f) + 1 <= 3  ->  f >= 1/3  ->  15 s left here + 20 s into the next
    assert d == Decision(False, 0, 35_000, 75_000)


def test_rolls_the_window_forward():
    state, d = sliding_window(WindowState(T0 // W - 1, 4, 9), 5, W, 1, T0 + 59_999)
    assert state == WindowState(T0 // W, 1, 4) and d.allowed  # cur became prev, the old prev is gone


def test_a_long_pause_forgets_everything():
    state, d = sliding_window(WindowState(T0 // W - 5, 5, 5), 5, W, 1, T0)
    assert state == WindowState(T0 // W, 1, 0) and d == Decision(True, 4, 0, 2 * W)


def test_time_going_backwards_stays_in_the_stored_window():
    state, d = sliding_window(WindowState(T0 // W + 1, 2, 0), 3, W, 1, T0 + 10)
    assert state == WindowState(T0 // W + 1, 3, 0) and d.allowed


def test_cost_counts_as_several_units():
    state, d = sliding_window(None, 10, W, 4, T0)
    assert d == Decision(True, 6, 0, 2 * W) and state.cur == 4
    _, d = sliding_window(state, 10, W, 7, T0)
    assert not d.allowed and d.remaining == 6


def test_reset_depends_on_what_is_still_counted():
    _, d = sliding_window(WindowState(T0 // W - 1, 2, 0), 5, W, 5, T0 + 10_000)  # prev=2 (cur rolled), cost 5 too big
    assert not d.allowed and d.reset_after_ms == W - 10_000  # only the previous window still counts


def test_a_cost_equal_to_the_limit_needs_an_empty_window():
    _, d = sliding_window(WindowState(T0 // W, 1, 0), 3, W, 3, T0 + 20_000)
    # next window: 1 * (1 - f) + 3 <= 3 -> f = 1 -> 40 s here + 60 s
    assert d == Decision(False, 2, 100_000, 100_000)  # 2 units are still free, just not 3
