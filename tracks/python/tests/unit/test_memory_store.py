"""M2: the in-memory store (given) runs your algorithms. These tests pin down what a store must do;
RedisStore (M3) must behave the same, which the integration tests check in Phase 4."""

from datetime import UTC, datetime

import pytest

from app.errors import DuplicateError, StoreUnavailable
from app.models import Decision, Policy, Tenant

TB = Policy("TOKEN_BUCKET", 2, 60)
SW = Policy("SLIDING_WINDOW", 2, 60)


def tenant(tid="acme", key_hash="h1") -> Tenant:
    now = datetime(2026, 10, 2, 9, 30, tzinfo=UTC)
    return Tenant(tid, "Acme", TB, key_hash, "rg_000000", now, now)


def test_create_find_list_and_duplicates(store):
    store.create_tenant(tenant("beta", "h2"))
    store.create_tenant(tenant("acme", "h1"))
    assert [t.id for t in store.list_tenants()] == ["acme", "beta"]
    assert store.find_by_key_hash("h2").id == "beta" and store.find_by_key_hash("nope") is None
    with pytest.raises(DuplicateError):
        store.create_tenant(tenant("acme", "h9"))


def test_save_moves_the_key(store):
    t = tenant()
    store.create_tenant(t)
    rotated = Tenant(t.id, t.name, t.policy, "h-new", "rg_111111", t.created_at, t.updated_at)
    store.save_tenant(rotated, t.key_hash)
    assert store.find_by_key_hash("h1") is None and store.find_by_key_hash("h-new").id == "acme"


def test_buckets_are_per_tenant_and_resource(store):
    assert store.check("acme", "a", TB, 2).allowed
    assert not store.check("acme", "a", TB, 1).allowed
    assert store.check("acme", "b", TB, 1).allowed
    assert store.check("beta", "a", TB, 1).allowed


def test_algorithms_have_separate_state(store):
    store.check("acme", "a", TB, 2)
    assert store.check("acme", "a", SW, 2) == Decision(True, 0, 0, 120_000)


def test_uses_the_injected_clock(store, clock):
    store.check("acme", "a", TB, 2)
    clock.advance(seconds=30)
    assert store.check("acme", "a", TB, 1).allowed


def test_usage_counts_allowed_and_denied_per_minute(store, clock):
    for _ in range(3):
        store.check("acme", "a", TB, 1)
    clock.advance(minutes=2)
    store.check("acme", "a", TB, 1)
    points = store.usage("acme", 3)
    assert [(p.allowed, p.denied) for p in points] == [(2, 1), (0, 0), (1, 0)]
    assert points[-1].minute == int(clock().timestamp()) // 60


def test_reset_forgets_only_that_tenant(store):
    store.check("acme", "a", TB, 2)
    store.check("acme-2", "a", TB, 2)
    assert store.reset("acme") == 1
    assert store.check("acme", "a", TB, 1).allowed and not store.check("acme-2", "a", TB, 1).allowed


def test_delete_removes_tenant_key_and_state(store):
    t = tenant()
    store.create_tenant(t)
    store.check("acme", "a", TB, 2)
    store.delete_tenant(t)
    assert store.get_tenant("acme") is None and store.find_by_key_hash("h1") is None
    assert store.check("acme", "a", TB, 1).remaining == 1


def test_an_outage_raises_store_unavailable(store):
    store.up = False
    assert not store.ping()
    with pytest.raises(StoreUnavailable):
        store.check("acme", "a", TB, 1)
    with pytest.raises(StoreUnavailable):
        store.get_tenant("acme")
