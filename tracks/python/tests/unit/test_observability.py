"""v1.1 (Phase 7): request IDs, JSON logs, /metrics and the LLM circuit breaker."""

import json
import logging
import sys

import pytest

from app.main import build_advisor
from app.metrics import COUNTERS, Metrics
from app.observability import JsonFormatter, incoming_or_new_id
from tests.conftest import ADMIN, Api, make_client, test_settings


def metric(client, name: str) -> int:
    body = client.get("/metrics").text
    return int(next(line.split()[1] for line in body.splitlines() if line.startswith(name + " ")))


# ---- request IDs and logs ----------------------------------------------------------------------
def test_a_safe_request_id_is_echoed_and_a_missing_one_is_minted(client):
    assert client.get("/health", headers={"X-Request-ID": "abc-123"}).headers["x-request-id"] == "abc-123"
    minted = client.get("/health").headers["x-request-id"]
    assert len(minted) == 36 and minted.count("-") == 4


@pytest.mark.parametrize("value", ["", "has space", "x" * 65, "evil\u00e9", "a;b"])
def test_unsafe_request_ids_are_replaced(value):
    assert incoming_or_new_id(value) != value


def test_errors_carry_the_request_id_too(client):
    r = client.get("/api/v1/nope", headers={"X-Request-ID": "trace-404"})
    assert r.status_code == 404 and r.headers["x-request-id"] == "trace-404"


def test_log_lines_are_json_with_the_request_fields():
    record = logging.LogRecord("rateguard.access", logging.INFO, __file__, 1, "request", None, None)
    record.fields = {"requestId": "r-1", "method": "GET", "path": "/health", "status": 200, "durationMs": 1.5}
    entry = json.loads(JsonFormatter().format(record))
    assert entry["msg"] == "request" and entry["level"] == "INFO" and entry["requestId"] == "r-1"
    assert entry["ts"].endswith("Z") and entry["status"] == 200


def test_exceptions_are_logged_as_one_json_line():
    try:
        raise ValueError("boom")
    except ValueError:
        record = logging.LogRecord("rateguard", logging.ERROR, __file__, 1, "failed", None, sys.exc_info())
    entry = json.loads(JsonFormatter().format(record))
    assert entry["level"] == "ERROR" and "ValueError: boom" in entry["error"]


def test_the_access_log_carries_the_request_id_but_never_the_api_key(api, client, caplog):
    key = api.tenant()["apiKey"]
    with caplog.at_level(logging.INFO, logger="rateguard.access"):
        client.get("/api/v1/demo/hello", headers={"X-Request-ID": "trace-me", "X-API-Key": key})
    fields = [r.fields for r in caplog.records if r.name == "rateguard.access"]
    assert fields[-1]["requestId"] == "trace-me" and fields[-1]["path"] == "/api/v1/demo/hello"
    assert fields[-1]["status"] == 200 and fields[-1]["durationMs"] >= 0
    assert key not in caplog.text


# ---- /metrics -------------------------------------------------------------------------------------
def test_metrics_endpoint_lists_every_counter(client):
    r = client.get("/metrics")
    assert r.status_code == 200 and r.headers["content-type"].startswith("text/plain")
    for name in COUNTERS:
        assert f"# TYPE {name} counter" in r.text and f"\n{name} 0" in r.text


def test_business_counters(api, client):
    key = api.tenant(limit=2)["apiKey"]
    api.tenant("beta")
    for _ in range(3):
        api.hello(key)
    api.check(key)  # another resource: its own bucket
    assert metric(client, "rateguard_tenants_created_total") == 2
    assert metric(client, "rateguard_decisions_allowed_total") == 3
    assert metric(client, "rateguard_decisions_denied_total") == 1
    assert metric(client, "rateguard_degraded_decisions_total") == 0


def test_metrics_render_format():
    m = Metrics()
    m.inc("rateguard_store_errors_total", 3)
    assert m.value("rateguard_store_errors_total") == 3
    assert "rateguard_store_errors_total 3\n" in m.render() and m.render().endswith("\n")


# ---- the LLM behind its breaker -------------------------------------------------------------------
def test_the_real_wiring_short_circuits_an_unreachable_llm(store, clock):
    metrics = Metrics()
    settings = test_settings(ai_provider="llm", llm_base_url="http://127.0.0.1:9/v1", llm_timeout_ms=500)
    with make_client(store, clock, advisor=build_advisor(settings, metrics), metrics=metrics) as client:
        api = Api(client)
        key = api.tenant()["apiKey"]
        api.hello(key)
        for _ in range(4):
            r = client.post("/api/v1/admin/tenants/acme/advice", headers=ADMIN)
            assert r.status_code == 200 and r.json()["fallback"] is True
        assert metrics.value("rateguard_llm_short_circuits_total") == 1
        assert metrics.value("rateguard_ai_fallback_total") == 4
