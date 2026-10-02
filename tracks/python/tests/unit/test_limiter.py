"""M4: the limiter service: checks, costs, headers and the fail-closed rule (ADR-0004)."""

from datetime import UTC, datetime

import pytest

from app.errors import ApiError
from app.limiter import LimiterService, rate_limit_headers, seconds_up
from app.models import Policy, Tenant


def tenant(limit=3, window=60, algorithm="TOKEN_BUCKET") -> Tenant:
    now = datetime(2026, 10, 2, 9, 30, tzinfo=UTC)
    return Tenant("acme", "Acme Corp", Policy(algorithm, limit, window), "h", "rg_000000", now, now)


@pytest.fixture
def limiter(store):
    return LimiterService(store)


def test_check_defaults_to_resource_default_and_cost_one(limiter):
    v = limiter.check(tenant(), None)
    assert (v.resource, v.cost, v.decision.allowed, v.decision.remaining) == ("default", 1, True, 2)


def test_check_reads_resource_and_cost(limiter):
    v = limiter.check(tenant(), {"resource": "orders.create", "cost": 3})
    assert v.resource == "orders.create" and v.decision.remaining == 0
    assert not limiter.check(tenant(), {"resource": "orders.create"}).decision.allowed
    assert limiter.check(tenant(), {"resource": "search"}).decision.allowed


def test_check_validates_the_body(limiter):
    for body in [["x"], {"cost": 0}, {"resource": "A"}]:
        with pytest.raises(ApiError) as e:
            limiter.check(tenant(), body)
        assert e.value.status == 400


def test_a_cost_above_the_limit_can_never_pass_so_it_is_422(limiter):
    with pytest.raises(ApiError) as e:
        limiter.check(tenant(limit=3), {"cost": 4})
    assert (e.value.status, e.value.slug) == (422, "cost-exceeds-limit")
    assert e.value.detail == "cost 4 is more than the limit of 3"


def test_enforce_raises_429_with_retry_after(limiter):
    for _ in range(3):
        limiter.enforce(tenant())
    with pytest.raises(ApiError) as e:
        limiter.enforce(tenant())
    assert (e.value.status, e.value.slug) == (429, "rate-limited")
    assert e.value.detail == "limit of 3 per 60 s reached, retry in 20 s"
    assert e.value.headers == {
        "X-RateLimit-Limit": "3",
        "X-RateLimit-Remaining": "0",
        "X-RateLimit-Reset": "60",
        "Retry-After": "20",
    }


def test_retry_after_is_at_least_one_second(limiter):
    t = tenant(limit=1000, window=1)
    for _ in range(1000):
        limiter.enforce(t)
    with pytest.raises(ApiError) as e:
        limiter.enforce(t)
    assert e.value.headers["Retry-After"] == "1"  # 1 ms rounds UP to 1 s, never 0


def test_a_store_outage_fails_closed_with_503(limiter, store):
    store.up = False
    with pytest.raises(ApiError) as e:
        limiter.check(tenant(), None)
    assert (e.value.status, e.value.slug) == (503, "limiter-unavailable")
    assert e.value.headers == {"Retry-After": "1"}


def test_headers_round_milliseconds_up():
    assert seconds_up(0) == 0 and seconds_up(1) == 1 and seconds_up(1000) == 1 and seconds_up(1001) == 2


def test_rate_limit_headers(limiter):
    v = limiter.check(tenant(limit=10, window=60), {"cost": 4})
    assert rate_limit_headers(v) == {"X-RateLimit-Limit": "10", "X-RateLimit-Remaining": "6", "X-RateLimit-Reset": "24"}
