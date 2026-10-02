"""v1.1 (Phase 7): what RateGuard does while Redis is unavailable (ADR-0006: failMode)."""

import pytest

from app.metrics import Metrics
from tests.conftest import ADMIN, Api, make_client


class Seconds:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


@pytest.fixture
def env(store, clock):
    metrics, now = Metrics(), Seconds()
    with make_client(store, clock, metrics=metrics, breaker_now=now) as client:
        yield Api(client), client, metrics, now


def test_closed_is_the_default_and_answers_503(env, store):
    api, _client, metrics, _now = env
    key = api.tenant()["apiKey"]
    assert api.hello(key).status_code == 200
    store.up = False
    r = api.hello(key)
    assert r.status_code == 503 and r.json()["type"].endswith("/limiter-unavailable")
    assert r.headers["retry-after"] == "1"
    assert metrics.value("rateguard_degraded_decisions_total") == 0


def test_open_allows_and_marks_the_answer_degraded(env, store):
    api, _client, metrics, _now = env
    key = api.tenant(limit=5, fail_mode="OPEN")["apiKey"]
    api.hello(key)  # Redis seen the tenant once: its policy is now known locally
    store.up = False
    for _ in range(10):  # more than the limit: OPEN does not count at all
        r = api.check(key, cost=2)
        assert r.status_code == 200
        assert r.json()["allowed"] is True and r.json()["degraded"] is True and r.json()["remaining"] == 3
        assert r.headers["x-ratelimit-degraded"] == "true"
    assert metrics.value("rateguard_degraded_decisions_total") == 10


def test_local_keeps_limiting_on_this_instance(env, store):
    api, _client, metrics, _now = env
    key = api.tenant(limit=2, fail_mode="LOCAL")["apiKey"]
    api.hello(key)
    store.up = False
    codes = [api.hello(key).status_code for _ in range(3)]
    assert codes == [200, 200, 429]
    denied = api.hello(key)
    assert denied.headers["x-ratelimit-degraded"] == "true" and denied.headers["retry-after"] == "30"
    first = api.check(key, resource="orders.create", cost=2)
    assert first.json()["allowed"] is True and first.json()["remaining"] == 0 and first.json()["degraded"] is True
    assert api.check(key, resource="orders.create").json()["allowed"] is False
    assert metrics.value("rateguard_decisions_denied_total") == 3


def test_an_unknown_key_during_an_outage_is_503_not_allowed(env, store):
    api, _client, _metrics, _now = env
    key = api.tenant(fail_mode="OPEN")["apiKey"]  # created, but never used, so not cached
    store.up = False
    assert api.hello(key).status_code == 503
    assert api.hello("rg_" + "f" * 32).status_code == 503


def test_the_breaker_stops_calling_redis_and_recovers(env, store):
    api, client, metrics, now = env
    key = api.tenant(fail_mode="OPEN")["apiKey"]
    api.hello(key)
    store.up = False
    for _ in range(6):
        assert api.hello(key).status_code == 200
    # 3 real failures open the circuit; the other calls are skipped (no timeout to wait for)
    assert metrics.value("rateguard_store_errors_total") == 3
    assert metrics.value("rateguard_store_short_circuits_total") >= 3
    assert client.get("/health").json()["status"] == "DEGRADED"
    store.up = True
    assert client.get("/health").json()["status"] == "DEGRADED"  # still open: no trial yet
    now.t += 5  # REDIS_BREAKER_COOLDOWN_MS
    assert client.get("/health").json() == {"status": "UP", "checks": {"redis": "UP"}}
    r = api.check(key)
    assert r.json()["degraded"] is False and "x-ratelimit-degraded" not in r.headers


def test_a_duplicate_id_does_not_open_the_breaker(env):
    api, client, metrics, _now = env
    api.tenant()
    body = {"id": "acme", "name": "Again", "policy": {"algorithm": "TOKEN_BUCKET", "limit": 1, "windowSeconds": 1}}
    for _ in range(4):
        assert client.post("/api/v1/admin/tenants", json=body, headers=ADMIN).status_code == 409
    assert client.get("/health").json()["status"] == "UP"
    assert metrics.value("rateguard_store_errors_total") == 0


def test_fail_mode_round_trips_and_is_validated(env):
    api, client, _metrics, _now = env
    assert api.tenant(fail_mode="LOCAL")["policy"]["failMode"] == "LOCAL"
    r = client.put(
        "/api/v1/admin/tenants/acme/policy",
        json={"algorithm": "TOKEN_BUCKET", "limit": 5, "windowSeconds": 60, "failMode": "OPEN"},
        headers=ADMIN,
    )
    assert r.status_code == 200 and r.json()["policy"]["failMode"] == "OPEN"
    bad = client.put(
        "/api/v1/admin/tenants/acme/policy",
        json={"algorithm": "TOKEN_BUCKET", "limit": 5, "windowSeconds": 60, "failMode": "MAYBE"},
        headers=ADMIN,
    )
    assert bad.status_code == 400
    assert "policy.failMode must be CLOSED, OPEN or LOCAL" in str(bad.json())


def test_the_advice_keeps_the_fail_mode(env):
    api, client, _metrics, _now = env
    key = api.tenant(limit=2, window=3600, fail_mode="LOCAL")["apiKey"]
    for _ in range(6):
        api.hello(key)
    body = client.post("/api/v1/admin/tenants/acme/advice", headers=ADMIN).json()
    assert body["suggestedPolicy"]["failMode"] == "LOCAL"
