"""Storage behind one interface: InMemoryStore (unit tests; v1.1 degraded mode) and RedisStore (M3).

A store keeps tenants, answers limiter checks atomically and counts usage per minute.
"""

from __future__ import annotations

import threading
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path
from typing import Protocol, TypeVar

import redis
from redis.backoff import NoBackoff
from redis.retry import Retry

from . import keys
from .algorithms import BucketState, WindowState, sliding_window, token_bucket
from .breaker import CircuitBreaker, CircuitOpenError
from .clock import Clock, to_ms, utc_now
from .errors import DuplicateError, StoreUnavailable
from .models import CLOSED, TOKEN_BUCKET, Decision, Policy, Tenant, UsagePoint

T = TypeVar("T")


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
    return {
        "id": t.id,
        "name": t.name,
        "algorithm": t.policy.algorithm,
        "limit": str(t.policy.limit),
        "windowSeconds": str(t.policy.window_seconds),
        "failMode": t.policy.fail_mode,
        "keyHash": t.key_hash,
        "keyPrefix": t.key_prefix,
        "createdAt": str(to_ms(t.created_at)),
        "updatedAt": str(to_ms(t.updated_at)),
    }


def tenant_from_hash(h: dict[str, str]) -> Tenant:
    # A tenant written by v1.0 has no failMode: it keeps the v1.0 behaviour (CLOSED).
    policy = Policy(h["algorithm"], int(h["limit"]), int(h["windowSeconds"]), h.get("failMode", CLOSED))
    return Tenant(h["id"], h["name"], policy, h["keyHash"], h["keyPrefix"], _dt(h["createdAt"]), _dt(h["updatedAt"]))


class RedisStore:
    """M3: the real store. Limiter checks are ONE Lua script call each (atomic, one round trip)."""

    def __init__(self, client: redis.Redis, lua_dir: str):
        self.r = client
        lua = Path(lua_dir)
        # register_script sends EVALSHA and falls back to EVAL (and caches) when Redis answers NOSCRIPT.
        self._token_bucket = client.register_script((lua / "token_bucket.lua").read_text())
        self._sliding_window = client.register_script((lua / "sliding_window.lua").read_text())

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
        def run():
            # SADD answers 0 when the id is already a member: Redis, not the app, decides the race.
            if self.r.sadd(keys.TENANTS, tenant.id) == 0:
                raise DuplicateError(tenant.id)
            pipe = self.r.pipeline(transaction=True)
            pipe.hset(keys.tenant_key(tenant.id), mapping=tenant_to_hash(tenant))
            pipe.set(keys.api_key_key(tenant.key_hash), tenant.id)
            pipe.execute()

        self._call(run)

    def get_tenant(self, tenant_id: str) -> Tenant | None:
        h = self._call(lambda: self.r.hgetall(keys.tenant_key(tenant_id)))
        return tenant_from_hash(h) if h else None

    def list_tenants(self) -> list[Tenant]:
        def run():
            ids = sorted(self.r.smembers(keys.TENANTS))
            pipe = self.r.pipeline(transaction=False)
            for tenant_id in ids:
                pipe.hgetall(keys.tenant_key(tenant_id))
            return [tenant_from_hash(h) for h in pipe.execute() if h]

        return self._call(run)

    def find_by_key_hash(self, key_hash: str) -> Tenant | None:
        tenant_id = self._call(lambda: self.r.get(keys.api_key_key(key_hash)))
        return self.get_tenant(tenant_id) if tenant_id else None

    def save_tenant(self, tenant: Tenant, old_key_hash: str) -> None:
        def run():
            pipe = self.r.pipeline(transaction=True)  # MULTI/EXEC: all or nothing
            pipe.hset(keys.tenant_key(tenant.id), mapping=tenant_to_hash(tenant))
            if old_key_hash != tenant.key_hash:
                pipe.delete(keys.api_key_key(old_key_hash))
                pipe.set(keys.api_key_key(tenant.key_hash), tenant.id)
            pipe.execute()

        self._call(run)

    def delete_tenant(self, tenant: Tenant) -> None:
        def run():
            pipe = self.r.pipeline(transaction=True)
            pipe.delete(keys.tenant_key(tenant.id), keys.api_key_key(tenant.key_hash), keys.usage_key(tenant.id))
            pipe.srem(keys.TENANTS, tenant.id)
            pipe.execute()

        self._call(run)
        self.reset(tenant.id)

    def reset(self, tenant_id: str) -> int:
        def run():
            # SCAN walks the keyspace in small steps; KEYS would block Redis for everyone.
            doomed = [k for k in self.r.scan_iter(match=keys.tenant_pattern(tenant_id), count=500)]
            doomed = [k for k in doomed if k != keys.usage_key(tenant_id)]
            return self.r.delete(*doomed) if doomed else 0

        return self._call(run)

    def check(self, tenant_id: str, resource: str, policy: Policy, cost: int, now_ms: int | None = None) -> Decision:
        script = self._token_bucket if policy.algorithm == TOKEN_BUCKET else self._sliding_window
        key_list = [keys.limiter_key(tenant_id, policy.algorithm, resource), keys.usage_key(tenant_id)]
        args = [policy.limit, policy.window_ms, cost] + ([now_ms] if now_ms is not None else [])
        allowed, remaining, retry, reset = self._call(lambda: script(keys=key_list, args=args))
        return Decision(allowed == 1, int(remaining), int(retry), int(reset))

    def usage(self, tenant_id: str, minutes: int) -> list[UsagePoint]:
        def run():
            seconds, micros = self.r.time()
            current = (seconds * 1000 + micros // 1000) // 60_000
            ring = self.r.hgetall(keys.usage_key(tenant_id))
            out = []
            for minute in range(current - minutes + 1, current + 1):
                slot = keys.usage_slot(minute)
                if ring.get(f"m{slot}") == str(minute):
                    out.append(UsagePoint(minute, int(ring.get(f"a{slot}", 0)), int(ring.get(f"d{slot}", 0))))
                else:
                    out.append(UsagePoint(minute, 0, 0))
            return out

        return self._call(run)


# ---------------------------------------------------------------------------------------------------
class GuardedStore:
    """v1.1: any store behind a circuit breaker (ADR-0006).

    While Redis keeps failing, every call would wait for REDIS_TIMEOUT_MS before failing. After
    ``threshold`` failures in a row the breaker opens and calls fail at once with StoreUnavailable,
    so the limiter can answer from the tenant's failMode in microseconds instead of seconds.
    DuplicateError is an answer from Redis, not a failure.
    """

    def __init__(self, inner: Store, breaker: CircuitBreaker, on_error: Callable[[], None] = lambda: None):
        self.inner = inner
        self.breaker = breaker
        self._on_error = on_error

    def _call(self, fn: Callable[[], T]) -> T:
        try:
            return self.breaker.call(fn)
        except CircuitOpenError as exc:
            raise StoreUnavailable(str(exc)) from None
        except StoreUnavailable:
            self._on_error()
            raise

    def ping(self) -> bool:
        def run() -> bool:
            if not self.inner.ping():
                raise StoreUnavailable("ping failed")
            return True

        try:
            return self._call(run)
        except StoreUnavailable:
            return False

    def create_tenant(self, tenant: Tenant) -> None:
        self._call(lambda: self.inner.create_tenant(tenant))

    def get_tenant(self, tenant_id: str) -> Tenant | None:
        return self._call(lambda: self.inner.get_tenant(tenant_id))

    def list_tenants(self) -> list[Tenant]:
        return self._call(self.inner.list_tenants)

    def find_by_key_hash(self, key_hash: str) -> Tenant | None:
        return self._call(lambda: self.inner.find_by_key_hash(key_hash))

    def save_tenant(self, tenant: Tenant, old_key_hash: str) -> None:
        self._call(lambda: self.inner.save_tenant(tenant, old_key_hash))

    def delete_tenant(self, tenant: Tenant) -> None:
        self._call(lambda: self.inner.delete_tenant(tenant))

    def reset(self, tenant_id: str) -> int:
        return self._call(lambda: self.inner.reset(tenant_id))

    def check(self, tenant_id: str, resource: str, policy: Policy, cost: int) -> Decision:
        return self._call(lambda: self.inner.check(tenant_id, resource, policy, cost))

    def usage(self, tenant_id: str, minutes: int) -> list[UsagePoint]:
        return self._call(lambda: self.inner.usage(tenant_id, minutes))
