"""M3: the Redis key layout (spec/architecture.md). One place, so no module builds keys by hand.

The {tenant} hash tag makes every limiter key of one tenant hash to the same Redis Cluster slot,
so a Lua script that touches the bucket and the usage ring stays legal on a cluster (ADR-0003).
"""

from __future__ import annotations

from .models import TOKEN_BUCKET

TENANTS = "rg:tenants"
USAGE_SLOTS = 180  # minutes kept in the usage ring (3 hours); the API reads at most 120


def tenant_key(tenant_id: str) -> str:
    return f"rg:tenant:{tenant_id}"


def api_key_key(key_hash: str) -> str:
    return f"rg:key:{key_hash}"


def limiter_key(tenant_id: str, algorithm: str, resource: str) -> str:
    kind = "tb" if algorithm == TOKEN_BUCKET else "sw"
    return f"rg:{{{tenant_id}}}:{kind}:{resource}"


def usage_key(tenant_id: str) -> str:
    return f"rg:{{{tenant_id}}}:usage"


def tenant_pattern(tenant_id: str) -> str:
    """SCAN pattern for every limiter key of one tenant (and nobody else's: ids cannot contain '}')."""
    return f"rg:{{{tenant_id}}}:*"


def usage_slot(minute: int) -> int:
    return minute % USAGE_SLOTS
