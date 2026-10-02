"""Phase 4: the RedisStore and the shared Lua scripts against a real Redis.

Needs Redis (docker compose up -d redis). Uses database 15 of TEST_REDIS_URL and FLUSHDB before each
test, so never point it at data you care about.
"""

import os
import random
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime

import pytest
import redis
from fastapi.testclient import TestClient

from app.algorithms import sliding_window, token_bucket
from app.config import Settings
from app.errors import DuplicateError, StoreUnavailable
from app.main import create_app
from app.metrics import Metrics
from app.models import Policy, Tenant
from app.store import RedisStore
from tests.conftest import ADMIN, Api, KeyGen, test_settings

pytestmark = pytest.mark.integration
URL = os.getenv("TEST_REDIS_URL", "redis://localhost:6379/15")
LUA = Settings.from_env().lua_dir
NOW = datetime(2026, 10, 2, 9, 30, tzinfo=UTC)


@pytest.fixture
def rstore() -> RedisStore:
    s = RedisStore.from_url(URL, 2000, LUA)
    s.r.flushdb()
    return s


TB5 = Policy("TOKEN_BUCKET", 5, 60)


def tenant(tid="acme", key_hash="h1", policy=TB5) -> Tenant:
    return Tenant(tid, "Acme Corp", policy, key_hash, "rg_000000", NOW, NOW)


def test_ping(rstore):
    assert rstore.ping()


def test_tenant_round_trip(rstore):
    t = tenant()
    rstore.create_tenant(t)
    assert rstore.get_tenant("acme") == t
    assert rstore.find_by_key_hash("h1") == t and rstore.find_by_key_hash("nope") is None
    rotated = Tenant(t.id, t.name, Policy("SLIDING_WINDOW", 9, 10), "h2", "rg_111111", t.created_at, NOW)
    rstore.save_tenant(rotated, "h1")
    assert rstore.find_by_key_hash("h1") is None and rstore.find_by_key_hash("h2") == rotated
    rstore.create_tenant(tenant("beta", "h3"))
    assert [x.id for x in rstore.list_tenants()] == ["acme", "beta"]
    rstore.delete_tenant(rotated)
    assert rstore.get_tenant("acme") is None and rstore.find_by_key_hash("h2") is None
    assert rstore.r.sismember("rg:tenants", "acme") == 0


def test_duplicate_ids_race_and_exactly_one_wins(rstore):
    def attempt(i):
        try:
            rstore.create_tenant(tenant("acme", f"h{i}"))
            return True
        except DuplicateError:
            return False

    with ThreadPoolExecutor(10) as pool:
        assert sum(pool.map(attempt, range(10))) == 1


@pytest.mark.parametrize("algorithm", ["TOKEN_BUCKET", "SLIDING_WINDOW"])
def test_lua_and_the_pure_function_agree_step_by_step(rstore, algorithm):
    rng = random.Random(42)
    fn = token_bucket if algorithm == "TOKEN_BUCKET" else sliding_window
    policy = Policy(algorithm, 7, 3)
    now, state = 1_790_933_400_000, None
    for _ in range(400):
        now += rng.choice([0, 0, 1, 37, 250, 999, 1500, 4000])
        cost = rng.choice([1, 1, 1, 2, 7])
        state, expected = fn(state, policy.limit, policy.window_ms, cost, now)
        assert rstore.check("acme", "x", policy, cost, now_ms=now) == expected, (now, cost)


@pytest.mark.parametrize("algorithm", ["TOKEN_BUCKET", "SLIDING_WINDOW"])
def test_concurrent_checks_never_exceed_the_limit(rstore, algorithm):
    policy = Policy(algorithm, 10, 60)
    with ThreadPoolExecutor(25) as pool:
        results = list(pool.map(lambda _: rstore.check("acme", "x", policy, 1).allowed, range(60)))
    assert results.count(True) == 10


def test_two_app_instances_share_one_limit(rstore):
    other = RedisStore.from_url(URL, 2000, LUA)  # its own connection pool, like a second container
    policy = Policy("TOKEN_BUCKET", 6, 60)
    allowed = [(rstore if i % 2 else other).check("acme", "x", policy, 1).allowed for i in range(12)]
    assert allowed.count(True) == 6


def test_keys_have_a_ttl_and_usage_is_recorded(rstore):
    rstore.check("acme", "x", Policy("TOKEN_BUCKET", 5, 60), 1)
    rstore.check("acme", "y", Policy("SLIDING_WINDOW", 5, 60), 1)
    assert 0 < rstore.r.pttl("rg:{acme}:tb:x") <= 13_000  # one token missing: full again in 12 s (+1 s)
    assert 0 < rstore.r.pttl("rg:{acme}:sw:y") <= 121_000
    assert 0 < rstore.r.pttl("rg:{acme}:usage") <= 10_800_000
    last = rstore.usage("acme", 2)[-1]
    assert (last.allowed, last.denied) == (2, 0)


def test_usage_reads_the_ring_for_the_current_minutes(rstore):
    policy = Policy("TOKEN_BUCKET", 1, 3600)
    for _ in range(3):
        rstore.check("acme", "x", policy, 1)
    points = rstore.usage("acme", 120)
    assert len(points) == 120 and (points[-1].allowed, points[-1].denied) == (1, 2)
    assert sum(p.allowed + p.denied for p in points[:-1]) == 0
    assert points[-1].minute - points[0].minute == 119


def test_reset_forgets_one_tenant_only(rstore):
    policy = Policy("TOKEN_BUCKET", 1, 3600)
    for tid in ["acme", "acme-2"]:
        rstore.check(tid, "x", policy, 1)
    assert rstore.reset("acme") == 1
    assert rstore.check("acme", "x", policy, 1).allowed and not rstore.check("acme-2", "x", policy, 1).allowed
    assert rstore.r.exists("rg:{acme}:usage") == 1  # history survives a reset


def test_the_redis_clock_is_used_without_an_override(rstore):
    policy = Policy("TOKEN_BUCKET", 2, 1)
    assert [rstore.check("acme", "x", policy, 1).allowed for _ in range(3)] == [True, True, False]
    time.sleep(0.6)
    assert rstore.check("acme", "x", policy, 1).allowed


def test_scripts_are_reloaded_after_a_script_flush(rstore):
    policy = Policy("SLIDING_WINDOW", 3, 60)
    rstore.check("acme", "x", policy, 1)
    rstore.r.script_flush()
    assert rstore.check("acme", "x", policy, 1).remaining == 1


def test_an_unreachable_redis_is_store_unavailable():
    dead = RedisStore.from_url("redis://127.0.0.1:1/0", 300, LUA)
    assert not dead.ping()
    with pytest.raises(StoreUnavailable):
        dead.check("acme", "x", Policy("TOKEN_BUCKET", 1, 1), 1)
    with pytest.raises(StoreUnavailable):
        dead.get_tenant("acme")


def test_the_whole_api_on_redis():
    redis.Redis.from_url(URL).flushdb()
    settings = test_settings(redis_url=URL)
    with TestClient(create_app(settings, new_key=KeyGen())) as client:
        api = Api(client)
        key = api.tenant(limit=3)["apiKey"]
        codes = [api.hello(key).status_code for _ in range(4)]
        assert codes == [200, 200, 200, 429]
        usage = client.get("/api/v1/admin/tenants/acme/usage?minutes=1", headers=ADMIN).json()
        assert usage["totals"] == {"allowed": 3, "denied": 1}
        assert client.get("/health").json() == {"status": "UP", "checks": {"redis": "UP"}}


def test_parallel_http_requests_on_redis():
    redis.Redis.from_url(URL).flushdb()
    with TestClient(create_app(test_settings(redis_url=URL), new_key=KeyGen())) as client:
        key = Api(client).tenant(algorithm="SLIDING_WINDOW", limit=8, window=60)["apiKey"]
        lock, codes = threading.Lock(), []

        def hit(_):
            code = client.get("/api/v1/demo/hello", headers={"X-API-Key": key}).status_code
            with lock:
                codes.append(code)

        with ThreadPoolExecutor(12) as pool:
            list(pool.map(hit, range(30)))
        assert codes.count(200) == 8 and codes.count(429) == 22


# ---- v1.1 (Phase 7) ------------------------------------------------------------------------------
def test_fail_mode_is_stored_and_v1_0_hashes_read_as_closed(rstore):
    t = tenant(policy=Policy("TOKEN_BUCKET", 5, 60, "LOCAL"))
    rstore.create_tenant(t)
    assert rstore.get_tenant("acme").policy.fail_mode == "LOCAL"
    rstore.r.hdel("rg:tenant:acme", "failMode")  # what a tenant written by v1.0 looks like
    assert rstore.get_tenant("acme").policy.fail_mode == "CLOSED"


def test_a_dead_redis_costs_three_timeouts_then_answers_fast():
    metrics = Metrics()
    settings = test_settings(redis_url="redis://127.0.0.1:1/0", redis_timeout_ms=300)
    with TestClient(create_app(settings, new_key=KeyGen(), metrics=metrics)) as client:
        started = time.monotonic()
        for _ in range(20):
            assert client.get("/health").json()["status"] == "DEGRADED"
        assert time.monotonic() - started < 5  # 20 x a full timeout would be 6 s or more
        assert metrics.value("rateguard_store_errors_total") == 3
        assert metrics.value("rateguard_store_short_circuits_total") == 17
