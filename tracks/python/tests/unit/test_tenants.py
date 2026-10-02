"""M4: the tenant service (framework-free business logic behind the admin API)."""

import pytest

from app.errors import ApiError
from app.models import Policy
from app.security import hash_api_key
from app.tenants import TenantService
from tests.conftest import KeyGen

BODY = {"id": "acme", "name": "Acme Corp", "policy": {"algorithm": "TOKEN_BUCKET", "limit": 5, "windowSeconds": 60}}


@pytest.fixture
def svc(store, clock):
    return TenantService(store, clock, KeyGen())


def status(fn, *args):
    with pytest.raises(ApiError) as e:
        fn(*args)
    return e.value.status, e.value.slug


def test_create_returns_the_key_once_and_stores_only_its_hash(svc, store):
    tenant, key = svc.create(BODY)
    assert key == "rg_00000000000000000000000000000001"
    assert tenant.key_hash == hash_api_key(key) and tenant.key_prefix == "rg_000000"
    assert tenant.policy == Policy("TOKEN_BUCKET", 5, 60)
    assert key not in repr(store.tenants)


def test_create_validates_everything(svc):
    assert status(svc.create, ["x"]) == (400, "validation-error")
    assert status(svc.create, {**BODY, "id": "A"}) == (400, "validation-error")
    assert status(svc.create, {**BODY, "policy": None}) == (400, "validation-error")


def test_duplicate_id_is_409(svc):
    svc.create(BODY)
    assert status(svc.create, BODY) == (409, "tenant-exists")


def test_get_unknown_or_impossible_ids_is_404(svc):
    assert status(svc.get, "nobody") == (404, "not-found")
    assert status(svc.get, "NOT A VALID ID") == (404, "not-found")


def test_set_policy_replaces_the_policy_and_bumps_updated_at(svc, clock):
    svc.create(BODY)
    clock.advance(minutes=5)
    t = svc.set_policy("acme", {"algorithm": "SLIDING_WINDOW", "limit": 50, "windowSeconds": 10})
    assert t.policy == Policy("SLIDING_WINDOW", 50, 10)
    assert t.updated_at > t.created_at and svc.get("acme").policy.limit == 50


def test_rotate_key_invalidates_the_old_key(svc):
    _, old = svc.create(BODY)
    _, new = svc.rotate_key("acme")
    assert new != old
    assert svc.authenticate(new).id == "acme"
    assert status(svc.authenticate, old) == (401, "invalid-api-key")


def test_authenticate_rejects_missing_malformed_and_unknown_keys(svc, store):
    svc.create(BODY)
    store.up = False  # malformed keys must not even reach the store
    for bad in [None, "", "Bearer x", "rg_short"]:
        assert status(svc.authenticate, bad) == (401, "invalid-api-key")
    store.up = True
    assert status(svc.authenticate, "rg_" + "f" * 32) == (401, "invalid-api-key")


def test_delete_and_reset(svc, store):
    svc.create(BODY)
    store.check("acme", "x", Policy("TOKEN_BUCKET", 5, 60), 5)
    assert svc.reset("acme") == 1
    svc.delete("acme")
    assert status(svc.get, "acme") == (404, "not-found")
    assert status(svc.reset, "acme") == (404, "not-found")


def test_usage_validates_minutes(svc):
    svc.create(BODY)
    tenant, minutes, points = svc.usage("acme", None)
    assert minutes == 60 and len(points) == 60 and tenant.id == "acme"
    assert status(svc.usage, "acme", "500") == (400, "validation-error")
