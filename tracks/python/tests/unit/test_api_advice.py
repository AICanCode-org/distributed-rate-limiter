"""M5: POST /admin/tenants/{id}/advice over HTTP. Advice is returned, never applied."""

from app.advisor import AdvisorService, LlmAdvisor, RuleAdvisor
from tests.conftest import ADMIN, Api, make_client


def test_advice_offline_for_a_busy_tenant(api, client):
    key = api.tenant(limit=2, window=3600)["apiKey"]
    for _ in range(5):
        api.check(key)
    r = client.post("/api/v1/admin/tenants/acme/advice?minutes=10", headers=ADMIN)
    assert r.status_code == 200
    body = r.json()
    assert (body["tenantId"], body["provider"], body["fallback"]) == ("acme", "rules", False)
    assert body["currentPolicy"] == {
        "algorithm": "TOKEN_BUCKET",
        "limit": 2,
        "windowSeconds": 3600,
        "failMode": "CLOSED",
    }
    assert body["stats"]["allowed"] == 2 and body["stats"]["denied"] == 3 and body["stats"]["minutes"] == 10
    assert body["action"] == "RAISE_LIMIT"
    assert body["suggestedPolicy"] == {
        "algorithm": "TOKEN_BUCKET",
        "limit": 20,
        "windowSeconds": 3600,
        "failMode": "CLOSED",
    }
    assert body["summary"].startswith("60% of requests were denied (3 of 5).")
    assert body["generatedAt"] == "2026-10-02T09:30:00.000Z"
    # nothing was applied
    assert client.get("/api/v1/admin/tenants/acme", headers=ADMIN).json()["policy"]["limit"] == 2


def test_advice_keep_has_no_suggested_policy(api, client):
    api.tenant()
    body = client.post("/api/v1/admin/tenants/acme/advice", headers=ADMIN).json()
    assert body["action"] == "KEEP" and body["suggestedPolicy"] is None and body["stats"]["minutes"] == 60


def test_advice_falls_back_when_the_llm_is_down(store, clock):
    class Down:
        def chat(self, *_):
            raise TimeoutError("LLM timed out")

    with make_client(store, clock, AdvisorService(RuleAdvisor(), LlmAdvisor(Down()))) as client:
        Api(client).tenant()
        r = client.post("/api/v1/admin/tenants/acme/advice", headers=ADMIN)
        assert r.status_code == 200 and r.json()["provider"] == "rules" and r.json()["fallback"] is True


def test_advice_errors(api, client):
    assert client.post("/api/v1/admin/tenants/acme/advice").status_code == 401
    assert client.post("/api/v1/admin/tenants/ghost/advice", headers=ADMIN).status_code == 404
    api.tenant()
    assert client.post("/api/v1/admin/tenants/acme/advice?minutes=121", headers=ADMIN).status_code == 400
