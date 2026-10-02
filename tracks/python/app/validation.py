"""M1: input validation. Every message is identical in all three tracks (clients and tests rely on them)."""

from __future__ import annotations

import re

from .errors import validation_error
from .models import ALGORITHMS, CLOSED, FAIL_MODES, Policy

TENANT_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$", re.ASCII)
RESOURCE_RE = re.compile(r"^[a-z0-9._:-]{1,64}$", re.ASCII)
MAX_LIMIT = 100_000
MAX_WINDOW_SECONDS = 86_400
MAX_COST = 1_000
MAX_NAME = 80
DEFAULT_RESOURCE = "default"
DEFAULT_MINUTES = 60
MAX_MINUTES = 120


def require_object(value: object) -> dict:
    if not isinstance(value, dict):
        raise validation_error("request body must be a JSON object")
    return value


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def tenant_id(value: object) -> str:
    """Tenant ids end up inside Redis keys, so only a small, safe alphabet is accepted (no ':', '{', '*')."""
    if not isinstance(value, str):
        raise validation_error("id is required")
    if not TENANT_ID_RE.match(value):
        raise validation_error("id must be 3-32 characters of a-z, 0-9 and inner hyphens")
    return value


def is_tenant_id(value: str) -> bool:
    return bool(TENANT_ID_RE.match(value))


def tenant_name(value: object) -> str:
    if not isinstance(value, str):
        raise validation_error("name is required")
    name = value.strip()
    if not 1 <= len(name) <= MAX_NAME:
        raise validation_error("name must be 1-80 characters")
    return name


def policy(value: object) -> Policy:
    if not isinstance(value, dict):
        raise validation_error("policy is required")
    algorithm = value.get("algorithm")
    if algorithm not in ALGORITHMS:
        raise validation_error("policy.algorithm must be TOKEN_BUCKET or SLIDING_WINDOW")
    limit = value.get("limit")
    if not _is_int(limit) or not 1 <= limit <= MAX_LIMIT:  # type: ignore[operator]
        raise validation_error("policy.limit must be an integer between 1 and 100000")
    window = value.get("windowSeconds")
    if not _is_int(window) or not 1 <= window <= MAX_WINDOW_SECONDS:  # type: ignore[operator]
        raise validation_error("policy.windowSeconds must be an integer between 1 and 86400")
    fail_mode = value.get("failMode", CLOSED)
    if fail_mode not in FAIL_MODES:
        raise validation_error("policy.failMode must be CLOSED, OPEN or LOCAL")
    return Policy(algorithm, limit, window, fail_mode)  # type: ignore[arg-type]


def resource(value: object) -> str:
    if value is None:
        return DEFAULT_RESOURCE
    if not isinstance(value, str) or not RESOURCE_RE.match(value):
        raise validation_error("resource must be 1-64 characters of a-z, 0-9, '.', '_', ':' and '-'")
    return value


def cost(value: object) -> int:
    if value is None:
        return 1
    if not _is_int(value) or not 1 <= value <= MAX_COST:  # type: ignore[operator]
        raise validation_error("cost must be an integer between 1 and 1000")
    return value  # type: ignore[return-value]


def minutes(value: str | None) -> int:
    """A query string: missing -> 60; ASCII digits only, 1..120."""
    if value is None:
        return DEFAULT_MINUTES
    if not (value.isascii() and value.isdigit()) or not 1 <= int(value) <= MAX_MINUTES:
        raise validation_error("minutes must be an integer between 1 and 120")
    return int(value)
