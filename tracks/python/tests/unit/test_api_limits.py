"""M4: the decision API and the rate-limited demo route over HTTP."""


def test_check_returns_the_decision_and_headers(api):
    key = api.tenant(limit=5)["apiKey"]
    r = api.check(key, resource="orders.create", cost=2)
    assert r.status_code == 200
    assert r.json() == {
        "allowed": True,
        "tenantId": "acme",
        "resource": "orders.create",
        "cost": 2,
        "algorithm": "TOKEN_BUCKET",
        "limit": 5,
        "remaining": 3,
        "resetAfterMs": 24_000,
        "retryAfterMs": 0,
    }
    assert r.headers["x-ratelimit-limit"] == "5" and r.headers["x-ratelimit-remaining"] == "3"
    assert r.headers["x-ratelimit-reset"] == "24"


def test_a_denied_check_is_still_a_200_answer(api):
    key = api.tenant(limit=1)["apiKey"]
    api.check(key)
    r = api.check(key)
    assert r.status_code == 200 and r.json()["allowed"] is False and r.json()["retryAfterMs"] == 60_000
    assert "retry-after" not in r.headers


def test_check_without_a_body_uses_the_defaults(api, client):
    key = api.tenant()["apiKey"]
    r = client.post("/api/v1/limits/check", headers={"X-API-Key": key})
    assert r.status_code == 200 and r.json()["resource"] == "default" and r.json()["cost"] == 1


def test_check_needs_a_valid_api_key(api, client):
    api.tenant()
    for headers in [{}, {"X-API-Key": "nope"}, {"X-API-Key": "rg_" + "0" * 31 + "9"}]:
        r = client.post("/api/v1/limits/check", headers=headers)
        assert r.status_code == 401 and r.json()["type"].endswith("/invalid-api-key")
        assert "www-authenticate" not in r.headers


def test_check_errors(api):
    key = api.tenant(limit=3)["apiKey"]
    assert api.check(key, cost=0).status_code == 400
    r = api.check(key, cost=4)
    assert r.status_code == 422 and r.json()["detail"] == "cost 4 is more than the limit of 3"


def test_sliding_window_tenant(api, clock):
    key = api.tenant(algorithm="SLIDING_WINDOW", limit=2, window=10)["apiKey"]
    assert [api.check(key).json()["allowed"] for _ in range(3)] == [True, True, False]
    clock.advance(seconds=20)
    assert api.check(key).json()["allowed"]


def test_hello_is_rate_limited_with_429_and_retry_after(api):
    key = api.tenant(limit=2)["apiKey"]
    ok = api.hello(key)
    assert ok.status_code == 200 and ok.json() == {"message": "Hello, Acme Corp!", "tenantId": "acme"}
    assert ok.headers["x-ratelimit-remaining"] == "1"
    api.hello(key)
    r = api.hello(key)
    assert r.status_code == 429 and r.headers["content-type"] == "application/problem+json"
    assert r.headers["retry-after"] == "30" and r.headers["x-ratelimit-remaining"] == "0"
    assert r.json() == {
        "type": "https://rateguard.example/problems/rate-limited",
        "title": "Too many requests",
        "status": 429,
        "detail": "limit of 2 per 60 s reached, retry in 30 s",
    }


def test_hello_and_check_use_separate_buckets(api):
    key = api.tenant(limit=1)["apiKey"]
    assert api.hello(key).status_code == 200
    assert api.check(key).json()["allowed"]  # resource "default", not "demo"


def test_tenants_do_not_share_limits(api):
    a = api.tenant("acme", limit=1)["apiKey"]
    b = api.tenant("beta", limit=1)["apiKey"]
    assert api.hello(a).status_code == 200 and api.hello(a).status_code == 429
    assert api.hello(b).status_code == 200


def test_store_outage_fails_closed(api, store):
    key = api.tenant()["apiKey"]
    store.up = False
    r = api.hello(key)
    assert r.status_code == 503 and r.json()["type"].endswith("/limiter-unavailable")
