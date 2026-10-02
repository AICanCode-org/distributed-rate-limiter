"""M1: API keys and the admin token. Keys are shown once and stored only as a SHA-256 hash."""

from __future__ import annotations

import hashlib
import hmac
import logging
import re
import secrets

from .errors import unauthorized

API_KEY_PREFIX = "rg_"
API_KEY_RE = re.compile(r"^rg_[0-9a-f]{32}$", re.ASCII)
log = logging.getLogger("rateguard")


def new_api_key() -> str:
    """'rg_' + 32 lowercase hex characters (128 random bits from a CSPRNG)."""
    return API_KEY_PREFIX + secrets.token_hex(16)


def hash_api_key(api_key: str) -> str:
    """Hex SHA-256. A fast hash is fine here: the key is 128 random bits, not a guessable password."""
    return hashlib.sha256(api_key.encode("utf-8")).hexdigest()


def key_prefix(api_key: str) -> str:
    """The first 9 characters ('rg_' + 6 hex): enough to recognise a key in a list, useless to an attacker."""
    return api_key[:9]


def looks_like_api_key(value: str | None) -> bool:
    return value is not None and bool(API_KEY_RE.match(value))


def constant_time_equals(a: str, b: str) -> bool:
    """Compare secrets without leaking, through timing, how many leading characters match."""
    return hmac.compare_digest(a.encode("utf-8"), b.encode("utf-8"))


def resolve_secret(value: str | None, name: str = "ADMIN_TOKEN") -> str:
    if value:
        return value
    log.warning("%s is not set: using a random value for this process (dev only)", name)
    return secrets.token_urlsafe(32)


class AdminAuth:
    """The admin API is protected by one shared bearer token (ADMIN_TOKEN)."""

    def __init__(self, token: str):
        self._token = token

    def require(self, authorization: str | None) -> None:
        if authorization is None or not authorization.startswith("Bearer "):
            raise unauthorized()
        if not constant_time_equals(authorization[len("Bearer ") :].strip(), self._token):
            raise unauthorized()
