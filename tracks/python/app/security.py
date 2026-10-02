"""M1: API keys and the admin token. Keys are shown once and stored only as a SHA-256 hash."""

from __future__ import annotations

import logging
import re
import secrets

API_KEY_PREFIX = "rg_"
API_KEY_RE = re.compile(r"^rg_[0-9a-f]{32}$", re.ASCII)
log = logging.getLogger("rateguard")


def new_api_key() -> str:
    """'rg_' + 32 lowercase hex characters (128 random bits from a CSPRNG)."""
    # TODO(phase-3 M1): 'rg_' + 32 lowercase hex characters from a CSPRNG (128 random bits). Never
    #   Math.random / random.random.
    raise NotImplementedError("TODO(phase-3 M1): new_api_key")


def hash_api_key(api_key: str) -> str:
    """Hex SHA-256. A fast hash is fine here: the key is 128 random bits, not a guessable password."""
    # TODO(phase-3 M1): hex SHA-256 of the key (UTF-8). Only this hash is stored; a fast hash is
    #   fine for 128 random bits.
    raise NotImplementedError("TODO(phase-3 M1): hash_api_key")


def key_prefix(api_key: str) -> str:
    """The first 9 characters ('rg_' + 6 hex): enough to recognise a key in a list, useless to an attacker."""
    # TODO(phase-3 M1): the first 9 characters ('rg_' + 6 hex): shown in lists so humans can tell
    #   keys apart.
    raise NotImplementedError("TODO(phase-3 M1): key_prefix")


def looks_like_api_key(value: str | None) -> bool:
    # TODO(phase-3 M1): true only for 'rg_' + exactly 32 lowercase hex characters (malformed keys
    #   never reach Redis).
    raise NotImplementedError("TODO(phase-3 M1): looks_like_api_key")


def constant_time_equals(a: str, b: str) -> bool:
    """Compare secrets without leaking, through timing, how many leading characters match."""
    # TODO(phase-3 M1): compare two secrets in constant time (hmac.compare_digest /
    #   crypto.timingSafeEqual on equal-length buffers / MessageDigest.isEqual), never ==.
    raise NotImplementedError("TODO(phase-3 M1): constant_time_equals")


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
        # TODO(phase-3 M1): the Authorization header must be 'Bearer <token>' and the token must
        #   equal ADMIN_TOKEN (constant time); anything else -> 401 unauthorized (the HTTP layer adds
        #   WWW-Authenticate: Bearer).
        raise NotImplementedError("TODO(phase-3 M1): AdminAuth.require")
