"""Always on: the app boots and reports the store's health."""


def test_health_is_up_with_the_store(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "UP", "checks": {"redis": "UP"}}


def test_health_is_degraded_but_200_when_the_store_is_down(client, store):
    # v1.1: the app still answers from each tenant's failMode, so it should stay in the load balancer
    store.up = False
    r = client.get("/health")
    assert r.status_code == 200 and r.json() == {"status": "DEGRADED", "checks": {"redis": "DOWN"}}


def test_unknown_routes_are_problem_json(client):
    r = client.get("/nope")
    assert r.status_code == 404
    assert r.headers["content-type"] == "application/problem+json"
    assert r.json()["type"] == "https://rateguard.example/problems/not-found"
