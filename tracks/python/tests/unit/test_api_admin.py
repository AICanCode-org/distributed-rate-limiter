"""M4: the admin API over HTTP (tenants, policies, keys, usage)."""

from tests.conftest import ADMIN

KEY1 = "rg_00000000000000000000000000000001"


def test_create_tenant_returns_201_location_and_the_key_once(api, client):
    r = client.post(
        "/api/v1/admin/tenants",
        headers=ADMIN,
        json={
            "id": "acme",
            "name": " Acme Corp ",
            "policy": {"algorithm": "TOKEN_BUCKET", "limit": 5, "windowSeconds": 60},
        },
    )
    assert r.status_code == 201 and r.headers["location"] == "/api/v1/admin/tenants/acme"
    assert r.json() == {
        "id": "acme",
        "name": "Acme Corp",
        "policy": {"algorithm": "TOKEN_BUCKET", "limit": 5, "windowSeconds": 60},
        "keyPrefix": "rg_000000",
        "createdAt": "2026-10-02T09:30:00.000Z",
        "updatedAt": "2026-10-02T09:30:00.000Z",
        "apiKey": KEY1,
    }
    got = client.get("/api/v1/admin/tenants/acme", headers=ADMIN)
    assert got.status_code == 200 and "apiKey" not in got.json() and KEY1 not in got.text


def test_the_admin_api_needs_the_admin_token(client):
    for headers in [
        {},
        {"Authorization": "Bearer wrong"},
        {"Authorization": "test-admin-token-at-least-32-characters"},
    ]:
        r = client.get("/api/v1/admin/tenants", headers=headers)
        assert r.status_code == 401 and r.headers["www-authenticate"] == "Bearer"
        assert r.headers["content-type"] == "application/problem+json"
        assert r.json()["type"].endswith("/unauthorized")


def test_a_tenant_api_key_is_not_an_admin_token(api, client):
    key = api.tenant()["apiKey"]
    assert client.get("/api/v1/admin/tenants", headers={"Authorization": f"Bearer {key}"}).status_code == 401


def test_list_is_sorted_by_id(api, client):
    api.tenant("zeta")
    api.tenant("beta")
    r = client.get("/api/v1/admin/tenants", headers=ADMIN)
    assert [t["id"] for t in r.json()["items"]] == ["beta", "zeta"]


def test_validation_and_conflicts_are_problems(api, client):
    r = client.post("/api/v1/admin/tenants", headers=ADMIN, json={"id": "acme", "name": "A", "policy": {}})
    assert r.status_code == 400 and r.json()["detail"] == "policy.algorithm must be TOKEN_BUCKET or SLIDING_WINDOW"
    r = client.post("/api/v1/admin/tenants", headers={**ADMIN, "Content-Type": "application/json"}, content=b"{nope")
    assert r.status_code == 400 and r.json()["detail"] == "request body must be a JSON object"
    api.tenant()
    r = client.post(
        "/api/v1/admin/tenants",
        headers=ADMIN,
        json={"id": "acme", "name": "Again", "policy": {"algorithm": "TOKEN_BUCKET", "limit": 1, "windowSeconds": 1}},
    )
    assert r.status_code == 409 and r.json()["type"].endswith("/tenant-exists")


def test_unknown_tenant_is_404(client):
    for method, path in [("GET", ""), ("DELETE", ""), ("POST", "/reset"), ("POST", "/rotate-key"), ("GET", "/usage")]:
        r = client.request(method, f"/api/v1/admin/tenants/ghost{path}", headers=ADMIN)
        assert r.status_code == 404 and r.json()["detail"] == "tenant not found", (method, path)


def test_put_policy(api, client, clock):
    api.tenant()
    clock.advance(minutes=1)
    r = client.put(
        "/api/v1/admin/tenants/acme/policy",
        headers=ADMIN,
        json={"algorithm": "SLIDING_WINDOW", "limit": 100, "windowSeconds": 3600},
    )
    assert r.status_code == 200
    assert r.json()["policy"] == {"algorithm": "SLIDING_WINDOW", "limit": 100, "windowSeconds": 3600}
    assert r.json()["updatedAt"] == "2026-10-02T09:31:00.000Z"
    bad = client.put("/api/v1/admin/tenants/acme/policy", headers=ADMIN, json={"algorithm": "TOKEN_BUCKET"})
    assert bad.status_code == 400


def test_rotate_key(api, client):
    old = api.tenant()["apiKey"]
    r = client.post("/api/v1/admin/tenants/acme/rotate-key", headers=ADMIN)
    assert r.status_code == 200 and r.json()["apiKey"] != old and r.json()["keyPrefix"] == "rg_000000"
    assert api.check(old).status_code == 401 and api.check(r.json()["apiKey"]).status_code == 200


def test_reset_gives_a_full_bucket_back(api, client):
    key = api.tenant(limit=1)["apiKey"]
    assert api.check(key).json()["allowed"] and not api.check(key).json()["allowed"]
    assert client.post("/api/v1/admin/tenants/acme/reset", headers=ADMIN).status_code == 204
    assert api.check(key).json()["allowed"]


def test_delete_tenant_revokes_its_key(api, client):
    key = api.tenant()["apiKey"]
    assert client.delete("/api/v1/admin/tenants/acme", headers=ADMIN).status_code == 204
    assert client.get("/api/v1/admin/tenants/acme", headers=ADMIN).status_code == 404
    assert api.check(key).status_code == 401


def test_usage_series(api, client, clock):
    key = api.tenant(limit=2, window=3600)["apiKey"]
    for _ in range(3):
        api.check(key)
    clock.advance(minutes=1)
    api.check(key)
    r = client.get("/api/v1/admin/tenants/acme/usage?minutes=3", headers=ADMIN)
    assert r.status_code == 200
    assert r.json() == {
        "tenantId": "acme",
        "minutes": 3,
        "totals": {"allowed": 2, "denied": 2},
        "series": [
            {"minute": "2026-10-02T09:29:00.000Z", "allowed": 0, "denied": 0},
            {"minute": "2026-10-02T09:30:00.000Z", "allowed": 2, "denied": 1},
            {"minute": "2026-10-02T09:31:00.000Z", "allowed": 0, "denied": 1},
        ],
    }
    assert client.get("/api/v1/admin/tenants/acme/usage?minutes=0", headers=ADMIN).status_code == 400


def test_store_outage_on_the_admin_api_is_503(api, client, store):
    api.tenant()
    store.up = False
    r = client.get("/api/v1/admin/tenants", headers=ADMIN)
    assert r.status_code == 503 and r.headers["retry-after"] == "1"
    assert r.json()["type"].endswith("/limiter-unavailable")
