"""v1.1 (Phase 7): the circuit breaker in front of Redis and the LLM."""

import pytest

from app.breaker import CircuitBreaker, CircuitOpenError
from app.errors import DuplicateError, StoreUnavailable


class Seconds:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


class Flaky:
    def __init__(self):
        self.calls = 0
        self.fail = True

    def __call__(self):
        self.calls += 1
        if self.fail:
            raise StoreUnavailable("connection refused")
        return "ok"


def make(**kwargs):
    now, dep, skipped = Seconds(), Flaky(), []
    breaker = CircuitBreaker(
        "redis", threshold=3, cooldown_s=5, now=now, on_short_circuit=lambda: skipped.append(1), **kwargs
    )
    return breaker, dep, now, skipped


def fail_times(breaker, dep, n):
    for _ in range(n):
        with pytest.raises(StoreUnavailable):
            breaker.call(dep)


def test_opens_after_three_failures_in_a_row_and_then_skips_the_call():
    breaker, dep, _now, skipped = make()
    fail_times(breaker, dep, 3)
    assert breaker.state == "open"
    with pytest.raises(CircuitOpenError):
        breaker.call(dep)
    assert dep.calls == 3 and skipped == [1]


def test_a_success_resets_the_count():
    breaker, dep, _now, _ = make()
    fail_times(breaker, dep, 2)
    dep.fail = False
    assert breaker.call(dep) == "ok"
    dep.fail = True
    fail_times(breaker, dep, 2)
    assert breaker.state == "closed"


def test_half_open_trial_success_closes_and_failure_reopens():
    breaker, dep, now, _ = make()
    fail_times(breaker, dep, 3)
    now.t += 5
    assert breaker.state == "half-open"
    fail_times(breaker, dep, 1)  # the one trial fails: open for another cooldown
    assert breaker.state == "open" and dep.calls == 4
    now.t += 5
    dep.fail = False
    assert breaker.call(dep) == "ok" and breaker.state == "closed"


def test_only_one_trial_call_while_half_open():
    breaker, _dep, now, skipped = make()
    fail_times(breaker, Flaky(), 3)
    now.t += 5

    def nested():  # a second caller arrives while the trial is still running
        with pytest.raises(CircuitOpenError):
            breaker.call(lambda: "second")
        return "trial"

    assert breaker.call(nested) == "trial" and skipped == [1]


def test_answers_that_are_not_failures_do_not_open_it():
    breaker, _dep, _now, _ = make(is_failure=lambda e: isinstance(e, StoreUnavailable))

    def duplicate():
        raise DuplicateError("acme")

    for _ in range(5):
        with pytest.raises(DuplicateError):
            breaker.call(duplicate)
    assert breaker.state == "closed"
