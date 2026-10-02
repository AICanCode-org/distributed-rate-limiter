"""M1: API keys are random, shown once, stored as a hash; the admin token is compared in constant time."""

import hashlib

import pytest

from app.errors import ApiError
from app.security import (
    AdminAuth,
    constant_time_equals,
    hash_api_key,
    key_prefix,
    looks_like_api_key,
    new_api_key,
    resolve_secret,
)


def test_new_keys_have_the_documented_shape_and_are_unique():
    keys = {new_api_key() for _ in range(200)}
    assert len(keys) == 200
    assert all(looks_like_api_key(k) for k in keys)


def test_hash_is_hex_sha256_of_the_whole_key():
    key = "rg_" + "ab" * 16
    assert hash_api_key(key) == hashlib.sha256(key.encode()).hexdigest()
    assert len(hash_api_key(key)) == 64 and hash_api_key(key) != key


def test_prefix_is_short_and_recognisable():
    assert key_prefix("rg_0123456789abcdef0123456789abcdef") == "rg_012345"


@pytest.mark.parametrize("bad", [None, "", "rg_123", "RG_" + "a" * 32, "rg_" + "A" * 32, "rg_" + "a" * 33, "x" * 35])
def test_looks_like_api_key_rejects_malformed_values(bad):
    assert not looks_like_api_key(bad)


def test_constant_time_equals():
    assert constant_time_equals("same-secret", "same-secret")
    assert not constant_time_equals("same-secret", "same-secreT")
    assert not constant_time_equals("short", "a-much-longer-value")


def test_admin_auth_accepts_only_the_exact_bearer_token():
    auth = AdminAuth("the-admin-token-0123456789-abcdefghij")
    auth.require("Bearer the-admin-token-0123456789-abcdefghij")
    for bad in [None, "", "the-admin-token-0123456789-abcdefghij", "Basic abc", "Bearer wrong", "Bearer "]:
        with pytest.raises(ApiError) as e:
            auth.require(bad)
        assert e.value.status == 401 and e.value.headers == {"WWW-Authenticate": "Bearer"}


def test_resolve_secret_keeps_a_configured_value_and_invents_one_otherwise():
    assert resolve_secret("configured") == "configured"
    a, b = resolve_secret(None), resolve_secret(None)
    assert a != b and len(a) >= 32
