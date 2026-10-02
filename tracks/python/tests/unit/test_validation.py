"""M1: input validation. The messages are part of the contract (identical in every track)."""

import pytest

from app import validation as v
from app.errors import ApiError
from app.models import Policy


def message(fn, *args) -> str:
    with pytest.raises(ApiError) as e:
        fn(*args)
    assert e.value.status == 400 and e.value.slug == "validation-error"
    return e.value.detail


def test_body_must_be_an_object():
    assert v.require_object({"a": 1}) == {"a": 1}
    assert message(v.require_object, ["a"]) == "request body must be a JSON object"
    assert message(v.require_object, None) == "request body must be a JSON object"


@pytest.mark.parametrize("good", ["acme", "a1b", "big-co-2", "x" * 32])
def test_tenant_id_accepts_the_safe_alphabet(good):
    assert v.tenant_id(good) == good


@pytest.mark.parametrize("bad", ["ab", "x" * 33, "Acme", "-acme", "acme-", "ac:me", "ac}me", "ac*me", "ac me", "äcme"])
def test_tenant_id_rejects_anything_that_could_break_a_redis_key(bad):
    assert message(v.tenant_id, bad) == "id must be 3-32 characters of a-z, 0-9 and inner hyphens"


def test_tenant_id_is_required():
    assert message(v.tenant_id, None) == "id is required"
    assert message(v.tenant_id, 42) == "id is required"


def test_is_tenant_id():
    assert v.is_tenant_id("acme") and not v.is_tenant_id("ACME")


def test_name_is_trimmed_and_limited():
    assert v.tenant_name("  Acme Corp ") == "Acme Corp"
    assert v.tenant_name("x" * 80) == "x" * 80
    assert message(v.tenant_name, None) == "name is required"
    assert message(v.tenant_name, "   ") == "name must be 1-80 characters"
    assert message(v.tenant_name, "x" * 81) == "name must be 1-80 characters"


def test_policy_happy_path():
    p = v.policy({"algorithm": "SLIDING_WINDOW", "limit": 100, "windowSeconds": 60})
    assert p == Policy("SLIDING_WINDOW", 100, 60) and p.window_ms == 60_000


def test_policy_is_required():
    assert message(v.policy, None) == "policy is required"
    assert message(v.policy, "TOKEN_BUCKET") == "policy is required"


@pytest.mark.parametrize("algorithm", [None, "token_bucket", "LEAKY_BUCKET", 1])
def test_policy_algorithm(algorithm):
    body = {"algorithm": algorithm, "limit": 1, "windowSeconds": 1}
    assert message(v.policy, body) == "policy.algorithm must be TOKEN_BUCKET or SLIDING_WINDOW"


@pytest.mark.parametrize("limit", [None, 0, 100_001, 1.5, "10", True])
def test_policy_limit_is_a_real_integer_in_range(limit):
    body = {"algorithm": "TOKEN_BUCKET", "limit": limit, "windowSeconds": 60}
    assert message(v.policy, body) == "policy.limit must be an integer between 1 and 100000"


@pytest.mark.parametrize("window", [None, 0, 86_401, 2.0, "60", False])
def test_policy_window_is_a_real_integer_in_range(window):
    body = {"algorithm": "TOKEN_BUCKET", "limit": 10, "windowSeconds": window}
    assert message(v.policy, body) == "policy.windowSeconds must be an integer between 1 and 86400"


def test_policy_bounds_are_inclusive():
    assert v.policy({"algorithm": "TOKEN_BUCKET", "limit": 100_000, "windowSeconds": 86_400}).limit == 100_000
    assert v.policy({"algorithm": "TOKEN_BUCKET", "limit": 1, "windowSeconds": 1}).window_seconds == 1


def test_resource_defaults_and_alphabet():
    assert v.resource(None) == "default"
    assert v.resource("orders.create:v2_x-1") == "orders.create:v2_x-1"
    for bad in ["", "Orders", "a*b", "a{b}", "x" * 65, 7, "a b"]:
        assert message(v.resource, bad) == "resource must be 1-64 characters of a-z, 0-9, '.', '_', ':' and '-'"


def test_cost_defaults_to_one_and_is_bounded():
    assert v.cost(None) == 1 and v.cost(1000) == 1000
    for bad in [0, 1001, 2.5, "3", True]:
        assert message(v.cost, bad) == "cost must be an integer between 1 and 1000"


def test_minutes_query_parameter():
    assert v.minutes(None) == 60 and v.minutes("1") == 1 and v.minutes("120") == 120
    for bad in ["0", "121", "-5", "1.5", "", "abc", "١٢"]:
        assert message(v.minutes, bad) == "minutes must be an integer between 1 and 120"
