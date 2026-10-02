"""M3: the Redis key layout (spec/architecture.md). One place, so no module builds keys by hand.

The {tenant} hash tag makes every limiter key of one tenant hash to the same Redis Cluster slot,
so a Lua script that touches the bucket and the usage ring stays legal on a cluster (ADR-0003).
"""

from __future__ import annotations

TENANTS = "rg:tenants"
USAGE_SLOTS = 180  # minutes kept in the usage ring (3 hours); the API reads at most 120


def tenant_key(tenant_id: str) -> str:
    # TODO(phase-3 M3): 'rg:tenant:<id>' (a hash with the tenant's fields).
    raise NotImplementedError("TODO(phase-3 M3): tenant_key")


def api_key_key(key_hash: str) -> str:
    # TODO(phase-3 M3): 'rg:key:<sha256 of the api key>' -> tenant id.
    raise NotImplementedError("TODO(phase-3 M3): api_key_key")


def limiter_key(tenant_id: str, algorithm: str, resource: str) -> str:
    # TODO(phase-3 M3): 'rg:{<id>}:tb:<resource>' for TOKEN_BUCKET, 'rg:{<id>}:sw:<resource>' for
    #   SLIDING_WINDOW. The {id} hash tag keeps a tenant's keys in one cluster slot (ADR-0003).
    raise NotImplementedError("TODO(phase-3 M3): limiter_key")


def usage_key(tenant_id: str) -> str:
    # TODO(phase-3 M3): 'rg:{<id>}:usage' (the per-minute usage ring, same hash tag as the limiter
    #   keys).
    raise NotImplementedError("TODO(phase-3 M3): usage_key")


def tenant_pattern(tenant_id: str) -> str:
    """SCAN pattern for every limiter key of one tenant (and nobody else's: ids cannot contain '}')."""
    # TODO(phase-3 M3): SCAN pattern for every key of one tenant: 'rg:{<id>}:*' (ids cannot contain
    #   '}', so no other tenant matches).
    raise NotImplementedError("TODO(phase-3 M3): tenant_pattern")


def usage_slot(minute: int) -> int:
    # TODO(phase-3 M3): minute % USAGE_SLOTS (180): the ring slot of an epoch minute.
    raise NotImplementedError("TODO(phase-3 M3): usage_slot")
