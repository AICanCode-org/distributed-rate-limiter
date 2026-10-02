"""M2: token bucket as a pure function. Time is just a number here: no sleeping, no flakiness."""

from app.algorithms import BucketState, token_bucket
from app.models import Decision

T0 = 1_790_933_400_000  # 2026-10-02T09:30:00Z in ms


def run(limit, window_ms, times, cost=1):
    state, out = None, []
    for t in times:
        state, d = token_bucket(state, limit, window_ms, cost, t)
        out.append(d)
    return state, out


def test_a_new_bucket_starts_full():
    state, d = token_bucket(None, 5, 60_000, 1, T0)
    assert d == Decision(True, 4, 0, 12_000)
    assert state == BucketState(4.0, T0)


def test_bursts_up_to_the_capacity_then_denies():
    _, out = run(5, 60_000, [T0] * 6)
    assert [d.allowed for d in out] == [True] * 5 + [False]
    assert [d.remaining for d in out] == [4, 3, 2, 1, 0, 0]


def test_a_denial_says_when_one_token_is_back():
    _, out = run(5, 60_000, [T0] * 6)
    assert out[-1] == Decision(False, 0, 12_000, 60_000)  # 60 s / 5 tokens = one token every 12 s


def test_refills_continuously():
    state, _ = run(5, 60_000, [T0] * 5)  # empty
    state, d = token_bucket(state, 5, 60_000, 1, T0 + 11_999)
    assert not d.allowed and d.retry_after_ms == 1
    state, d = token_bucket(state, 5, 60_000, 1, T0 + 12_000)
    assert d.allowed and d.remaining == 0


def test_never_refills_above_the_capacity():
    state, _ = run(5, 60_000, [T0])
    state, d = token_bucket(state, 5, 60_000, 1, T0 + 3_600_000)  # an hour later
    assert d.remaining == 4 and state.tokens == 4.0


def test_cost_spends_several_tokens_at_once():
    state, d = token_bucket(None, 10, 10_000, 4, T0)
    assert d == Decision(True, 6, 0, 4_000)
    state, d = token_bucket(state, 10, 10_000, 7, T0)
    assert d == Decision(False, 6, 1_000, 4_000)  # a denial spends nothing: 6 tokens left


def test_fractional_tokens_are_kept_between_calls():
    state, _ = token_bucket(None, 3, 1_000, 3, T0)  # empty; refill 3 per second
    state, d = token_bucket(state, 3, 1_000, 1, T0 + 200)  # 0.6 tokens
    assert not d.allowed and d.retry_after_ms == 134  # ceil(0.4 * 1000 / 3)
    state, d = token_bucket(state, 3, 1_000, 1, T0 + 334)  # 0.6 + 0.402
    assert d.allowed


def test_time_going_backwards_never_creates_tokens():
    state, _ = run(2, 60_000, [T0, T0])  # empty
    state, d = token_bucket(state, 2, 60_000, 1, T0 - 30_000)
    assert not d.allowed and state.ts == T0
    _, d = token_bucket(state, 2, 60_000, 1, T0 + 30_000)
    assert d.allowed  # exactly one token came back in the 30 s after T0


def test_reset_is_the_time_until_the_bucket_is_full_again():
    _, out = run(4, 8_000, [T0, T0])
    assert out[-1].reset_after_ms == 4_000  # 2 tokens missing, 2 s each


def test_a_slow_policy_one_per_hour():
    state, d = token_bucket(None, 1, 3_600_000, 1, T0)
    assert d == Decision(True, 0, 0, 3_600_000)
    _, d = token_bucket(state, 1, 3_600_000, 1, T0 + 60_000)
    assert d == Decision(False, 0, 3_540_000, 3_540_000)
