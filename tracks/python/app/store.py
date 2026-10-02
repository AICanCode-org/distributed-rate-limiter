"""Storage behind one interface: InMemoryStore (unit tests; v1.1 degraded mode) and RedisStore (M3).

A store keeps tenants, answers limiter checks atomically and counts usage per minute.
"""

from __future__ import annotations

import threading
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path
from typing import Protocol

import redis
from redis.backoff import NoBackoff
from redis.retry import Retry

from . import keys
from .algorithms import BucketState, WindowState, sliding_window, token_bucket
from .clock import Clock, to_ms, utc_now
from .errors import DuplicateError, StoreUnavailable
from .models import TOKEN_BUCKET, Decision, Policy, Tenant, UsagePoint


class Store(Protocol):
    def ping(self) -> bool: ...
    def create_tenant(self, tenant: Tenant) -> None: ...  # DuplicateError when the id is taken
    def get_tenant(self, tenant_id: str) -> Tenant | None: ...
    def list_tenants(self) -> list[Tenant]: ...
    def find_by_key_hash(self, key_hash: str) -> Tenant | None: ...
    def save_tenant(self, tenant: Tenant, old_key_hash: str) -> None: ...  # policy/name/key changed
    def delete_tenant(self, tenant: Tenant) -> None: ...
    def reset(self, tenant_id: str) -> int: ...  # number of limiter keys removed
    def check(self, tenant_id: str, resource: str, policy: Policy, cost: int) -> Decision: ...
    def usage(self, tenant_id: str, minutes: int) -> list[UsagePoint]: ...  # oldest first, zeros included


# ---------------------------------------------------------------------------------------------------
class InMemoryStore:
    """Everything in dictionaries behind one lock, using the injected clock. Read it: it shows exactly
    what each RedisStore method must do."""

    def __init__(self, clock: Clock = utc_now):
        self._clock = clock
        self._lock = threading.Lock()
        self.tenants: dict[str, Tenant] = {}
        self.keys: dict[str, str] = {}  # key hash -> tenant id
        self.limiters: dict[str, BucketState | WindowState] = {}
        self.usage_counts: dict[str, dict[int, list[int]]] = {}  # tenant -> minute -> [allowed, denied]
        self.up = True  # tests flip this to simulate an outage

    def _guard(self) -> None:
        if not self.up:
            raise StoreUnavailable("in-memory store is down (test)")

    def ping(self) -> bool:
        return self.up

    def create_tenant(self, tenant: Tenant) -> None:
        with self._lock:
            self._guard()
            if tenant.id in self.tenants:
                raise DuplicateError(tenant.id)
            self.tenants[tenant.id] = tenant
            self.keys[tenant.key_hash] = tenant.id

    def get_tenant(self, tenant_id: str) -> Tenant | None:
        self._guard()
        return self.tenants.get(tenant_id)

    def list_tenants(self) -> list[Tenant]:
        self._guard()
        return sorted(self.tenants.values(), key=lambda t: t.id)

    def find_by_key_hash(self, key_hash: str) -> Tenant | None:
        self._guard()
        tenant_id = self.keys.get(key_hash)
        return self.tenants.get(tenant_id) if tenant_id else None

    def save_tenant(self, tenant: Tenant, old_key_hash: str) -> None:
        with self._lock:
            self._guard()
            if old_key_hash != tenant.key_hash:
                self.keys.pop(old_key_hash, None)
                self.keys[tenant.key_hash] = tenant.id
            self.tenants[tenant.id] = tenant

    def delete_tenant(self, tenant: Tenant) -> None:
        with self._lock:
            self._guard()
            self.tenants.pop(tenant.id, None)
            self.keys.pop(tenant.key_hash, None)
            self.usage_counts.pop(tenant.id, None)
            self._reset(tenant.id)

    def reset(self, tenant_id: str) -> int:
        with self._lock:
            self._guard()
            return self._reset(tenant_id)

    def _reset(self, tenant_id: str) -> int:
        prefix = keys.tenant_pattern(tenant_id)[:-1]
        doomed = [k for k in self.limiters if k.startswith(prefix)]
        for k in doomed:
            del self.limiters[k]
        return len(doomed)

    def check(self, tenant_id: str, resource: str, policy: Policy, cost: int) -> Decision:
        with self._lock:
            self._guard()
            now = to_ms(self._clock())
            key = keys.limiter_key(tenant_id, policy.algorithm, resource)
            state = self.limiters.get(key)
            if policy.algorithm == TOKEN_BUCKET:
                bucket = state if isinstance(state, BucketState) else None
                new_state, decision = token_bucket(bucket, policy.limit, policy.window_ms, cost, now)
            else:
                window = state if isinstance(state, WindowState) else None
                new_state, decision = sliding_window(window, policy.limit, policy.window_ms, cost, now)
            self.limiters[key] = new_state
            counts = self.usage_counts.setdefault(tenant_id, {}).setdefault(now // 60_000, [0, 0])
            counts[0 if decision.allowed else 1] += 1
            return decision

    def usage(self, tenant_id: str, minutes: int) -> list[UsagePoint]:
        self._guard()
        current = to_ms(self._clock()) // 60_000
        counts = self.usage_counts.get(tenant_id, {})
        out = []
        for minute in range(current - minutes + 1, current + 1):
            allowed, denied = counts.get(minute, [0, 0])
            out.append(UsagePoint(minute, allowed, denied))
        return out


# ---------------------------------------------------------------------------------------------------
def _dt(ms: str) -> datetime:
    return datetime.fromtimestamp(int(ms) / 1000, UTC)


def tenant_to_hash(t: Tenant) -> dict[str, str]:
    # TODO(phase-3 M3): the tenant as a flat string map for HSET: id, name, algorithm, limit,
    #   windowSeconds, keyHash, keyPrefix, createdAt/updatedAt as epoch ms.
    raise NotImplementedError("TODO(phase-3 M3): tenant_to_hash")


def tenant_from_hash(h: dict[str, str]) -> Tenant:
    # TODO(phase-3 M3): the reverse of tenant_to_hash (numbers parsed, epoch ms back to UTC
    #   datetimes).
    raise NotImplementedError("TODO(phase-3 M3): tenant_from_hash")


class RedisStore:
    """M3: the real store. Limiter checks are ONE Lua script call each (atomic, one round trip)."""

    def __init__(self, client: redis.Redis, lua_dir: str):
        self.r = client
        self.lua_dir = Path(lua_dir)
        # TODO(phase-3 M3): load spec/lua/token_bucket.lua and sliding_window.lua (you wrote them in Phase 2)
        #   with client.register_script(...): it sends EVALSHA and falls back to EVAL when Redis answers NOSCRIPT.

    @staticmethod
    def from_url(url: str, timeout_ms: int, lua_dir: str) -> RedisStore:
        client = redis.Redis.from_url(
            url,
            decode_responses=True,
            socket_timeout=timeout_ms / 1000,
            socket_connect_timeout=timeout_ms / 1000,
            retry=Retry(NoBackoff(), 0),  # fail fast: the caller decides what an outage means
        )
        return RedisStore(client, lua_dir)

    def _call(self, fn: Callable):
        try:
            return fn()
        except redis.RedisError as exc:
            raise StoreUnavailable(str(exc)) from exc

    def ping(self) -> bool:
        try:
            return bool(self.r.ping())
        except redis.RedisError:
            return False

    def create_tenant(self, tenant: Tenant) -> None:
        # TODO(phase-3 M3): SADD rg:tenants <id>; 0 means the id exists -> DuplicateError (Redis
        #   decides the race, not the app). Then in ONE MULTI/EXEC: HSET the tenant hash and SET
        #   rg:key:<hash> <id>.
        raise NotImplementedError("TODO(phase-3 M3): RedisStore.create_tenant")

    def get_tenant(self, tenant_id: str) -> Tenant | None:
        # TODO(phase-3 M3): HGETALL rg:tenant:<id>; empty -> null.
        raise NotImplementedError("TODO(phase-3 M3): RedisStore.get_tenant")

    def list_tenants(self) -> list[Tenant]:
        # TODO(phase-3 M3): SMEMBERS rg:tenants, sorted by id, then HGETALL each one in a pipeline.
        raise NotImplementedError("TODO(phase-3 M3): RedisStore.list_tenants")

    def find_by_key_hash(self, key_hash: str) -> Tenant | None:
        # TODO(phase-3 M3): GET rg:key:<hash> -> tenant id -> get_tenant; unknown -> null.
        raise NotImplementedError("TODO(phase-3 M3): RedisStore.find_by_key_hash")

    def save_tenant(self, tenant: Tenant, old_key_hash: str) -> None:
        # TODO(phase-3 M3): ONE MULTI/EXEC: HSET the hash; if the key hash changed, DEL the old
        #   rg:key:<old> and SET the new one (the old key stops working at once).
        raise NotImplementedError("TODO(phase-3 M3): RedisStore.save_tenant")

    def delete_tenant(self, tenant: Tenant) -> None:
        # TODO(phase-3 M3): ONE MULTI/EXEC: DEL the tenant hash, its rg:key and its usage ring, SREM
        #   from rg:tenants; then reset() the limiter keys.
        raise NotImplementedError("TODO(phase-3 M3): RedisStore.delete_tenant")

    def reset(self, tenant_id: str) -> int:
        # TODO(phase-3 M3): SCAN (never KEYS) for tenant_pattern, keep the usage ring, DEL the rest;
        #   return how many were deleted.
        raise NotImplementedError("TODO(phase-3 M3): RedisStore.reset")

    def check(self, tenant_id: str, resource: str, policy: Policy, cost: int, now_ms: int | None = None) -> Decision:
        # TODO(phase-3 M3): ONE script call (EVALSHA, EVAL on NOSCRIPT): token_bucket.lua or
        #   sliding_window.lua with KEYS = [limiter key, usage key] and ARGV = [limit, window_ms, cost
        #   (, now_ms for tests)]; it returns [allowed 0/1, remaining, retryAfterMs, resetAfterMs].
        raise NotImplementedError("TODO(phase-3 M3): RedisStore.check")

    def usage(self, tenant_id: str, minutes: int) -> list[UsagePoint]:
        # TODO(phase-3 M3): TIME gives Redis' clock; HGETALL the ring; for each of the last n
        #   minutes slot = usage_slot(minute): use a<slot>/d<slot> only if m<slot> == minute, else
        #   zeros. Oldest first.
        raise NotImplementedError("TODO(phase-3 M3): RedisStore.usage")
