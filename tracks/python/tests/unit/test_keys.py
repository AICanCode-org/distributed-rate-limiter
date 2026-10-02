"""M3: the Redis key layout (spec/architecture.md, ADR-0003)."""

from app import keys


def test_tenant_and_api_key_keys():
    assert keys.TENANTS == "rg:tenants"
    assert keys.tenant_key("acme") == "rg:tenant:acme"
    assert keys.api_key_key("ab12") == "rg:key:ab12"


def test_limiter_keys_carry_the_tenant_hash_tag():
    assert keys.limiter_key("acme", "TOKEN_BUCKET", "orders.create") == "rg:{acme}:tb:orders.create"
    assert keys.limiter_key("acme", "SLIDING_WINDOW", "default") == "rg:{acme}:sw:default"
    assert keys.usage_key("acme") == "rg:{acme}:usage"


def test_the_scan_pattern_matches_one_tenant_only():
    import fnmatch

    pattern = keys.tenant_pattern("acme")
    assert fnmatch.fnmatchcase("rg:{acme}:tb:x", pattern)
    assert not fnmatch.fnmatchcase("rg:{acme-2}:tb:x", pattern)
    assert not fnmatch.fnmatchcase("rg:tenant:acme", pattern)


def test_usage_ring_slots():
    assert keys.USAGE_SLOTS == 180
    assert keys.usage_slot(29_848_884) == 29_848_884 % 180 and keys.usage_slot(180) == 0
