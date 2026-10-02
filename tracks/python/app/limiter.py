"""M4: the limiter service: validate a check, ask the store, turn the decision into HTTP headers."""

from __future__ import annotations

import math

from . import validation as v
from .errors import ApiError, StoreUnavailable, cost_exceeds_limit, limiter_unavailable, rate_limited
from .metrics import Metrics
from .models import CLOSED, OPEN, Decision, Tenant, Verdict
from .store import InMemoryStore, Store

DEMO_RESOURCE = "demo"


def seconds_up(ms: int) -> int:
    return math.ceil(ms / 1000)


class LimiterService:
    def __init__(self, store: Store, local: InMemoryStore | None = None, metrics: Metrics | None = None):
        self.store = store
        # v1.1: the per-instance limiter used by failMode LOCAL while Redis is unavailable
        self.local = local or InMemoryStore()
        self.metrics = metrics or Metrics()

    def check(self, tenant: Tenant, payload: object) -> Verdict:
        """POST /limits/check: the decision API other services call before doing the work."""
        body = {} if payload is None else v.require_object(payload)
        resource = v.resource(body.get("resource"))
        cost = v.cost(body.get("cost"))
        return self.decide(tenant, resource, cost)

    def decide(self, tenant: Tenant, resource: str, cost: int) -> Verdict:
        if cost > tenant.policy.limit:
            raise cost_exceeds_limit(cost, tenant.policy.limit)
        try:
            decision = self.store.check(tenant.id, resource, tenant.policy, cost)
        except StoreUnavailable:
            decision = self._degraded(tenant, resource, cost)
        self.metrics.inc(
            "rateguard_decisions_allowed_total" if decision.allowed else "rateguard_decisions_denied_total"
        )
        return Verdict(tenant, resource, cost, decision)

    def _degraded(self, tenant: Tenant, resource: str, cost: int) -> Decision:
        """v1.1: Redis is unavailable, so the tenant's failMode decides (ADR-0006)."""
        mode = tenant.policy.fail_mode
        if mode == CLOSED:
            # without its store the limiter cannot promise anything (ADR-0004)
            raise limiter_unavailable() from None
        self.metrics.inc("rateguard_degraded_decisions_total")
        if mode == OPEN:
            return Decision(True, tenant.policy.limit - cost, 0, 0, degraded=True)
        # LOCAL: an exact limit per instance (N instances may allow up to N x limit in total)
        d = self.local.check(tenant.id, resource, tenant.policy, cost)
        return Decision(d.allowed, d.remaining, d.retry_after_ms, d.reset_after_ms, degraded=True)

    def enforce(self, tenant: Tenant, resource: str = DEMO_RESOURCE) -> Verdict:
        """Middleware style: a denied request becomes a 429 problem with Retry-After."""
        verdict = self.decide(tenant, resource, 1)
        if not verdict.decision.allowed:
            raise self.too_many(verdict)
        return verdict

    @staticmethod
    def too_many(verdict: Verdict) -> ApiError:
        retry_s = max(1, seconds_up(verdict.decision.retry_after_ms))
        headers = {**rate_limit_headers(verdict), "Retry-After": str(retry_s)}
        policy = verdict.tenant.policy
        return rate_limited(policy.limit, policy.window_seconds, retry_s, headers)


def rate_limit_headers(verdict: Verdict) -> dict[str, str]:
    d = verdict.decision
    headers = {
        "X-RateLimit-Limit": str(verdict.tenant.policy.limit),
        "X-RateLimit-Remaining": str(d.remaining),
        "X-RateLimit-Reset": str(seconds_up(d.reset_after_ms)),
    }
    if d.degraded:
        headers["X-RateLimit-Degraded"] = "true"
    return headers
