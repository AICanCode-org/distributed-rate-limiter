"""M1: input validation. Every message is identical in all three tracks (clients and tests rely on them)."""

from __future__ import annotations

import re

from .errors import validation_error
from .models import Policy

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
    # TODO(phase-3 M1): a string (400 'id is required') matching TENANT_ID_RE: 3-32 characters of
    #   a-z, 0-9 and inner hyphens (400 'id must be 3-32 characters of a-z, 0-9 and inner hyphens').
    #   Ids end up inside Redis keys, so ':', '{' and '*' must never get through.
    raise NotImplementedError("TODO(phase-3 M1): tenant_id")


def is_tenant_id(value: str) -> bool:
    # TODO(phase-3 M1): true when the string matches TENANT_ID_RE (used to answer 404 for impossible
    #   ids without asking Redis).
    raise NotImplementedError("TODO(phase-3 M1): is_tenant_id")


def tenant_name(value: object) -> str:
    # TODO(phase-3 M1): a string (400 'name is required'), trimmed, 1-80 characters counted as code
    #   points (400 'name must be 1-80 characters').
    raise NotImplementedError("TODO(phase-3 M1): tenant_name")


def policy(value: object) -> Policy:
    # TODO(phase-3 M1): an object (400 'policy is required'); algorithm TOKEN_BUCKET or
    #   SLIDING_WINDOW; limit an INTEGER 1..MAX_LIMIT (booleans and floats are not integers);
    #   windowSeconds an integer 1..MAX_WINDOW_SECONDS. Messages: see the validation tests.
    raise NotImplementedError("TODO(phase-3 M1): policy")


def resource(value: object) -> str:
    # TODO(phase-3 M1): missing/null -> DEFAULT_RESOURCE ('default'); otherwise a string matching
    #   RESOURCE_RE (1-64 of a-z 0-9 . _ : -), else 400.
    raise NotImplementedError("TODO(phase-3 M1): resource")


def cost(value: object) -> int:
    # TODO(phase-3 M1): missing/null -> 1; otherwise an integer 1..MAX_COST (1000), else 400 'cost
    #   must be an integer between 1 and 1000'.
    raise NotImplementedError("TODO(phase-3 M1): cost")


def minutes(value: str | None) -> int:
    """A query string: missing -> 60; ASCII digits only, 1..120."""
    # TODO(phase-3 M1): a query string: missing -> DEFAULT_MINUTES (60); ASCII digits only and
    #   1..MAX_MINUTES (120), else 400 'minutes must be an integer between 1 and 120'.
    raise NotImplementedError("TODO(phase-3 M1): minutes")
