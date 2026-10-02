"""M4: the limiter service: validate a check, ask the store, turn the decision into HTTP headers."""

from __future__ import annotations

import math

from . import validation as v
from .errors import ApiError, StoreUnavailable, cost_exceeds_limit, limiter_unavailable, rate_limited
from .models import Tenant, Verdict
from .store import Store

DEMO_RESOURCE = "demo"


def seconds_up(ms: int) -> int:
    return math.ceil(ms / 1000)


class LimiterService:
    def __init__(self, store: Store):
        self.store = store

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
            # v1.0 fails CLOSED: without its store the limiter cannot promise anything (ADR-0004).
            raise limiter_unavailable() from None
        return Verdict(tenant, resource, cost, decision)

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
    return {
        "X-RateLimit-Limit": str(verdict.tenant.policy.limit),
        "X-RateLimit-Remaining": str(d.remaining),
        "X-RateLimit-Reset": str(seconds_up(d.reset_after_ms)),
    }
