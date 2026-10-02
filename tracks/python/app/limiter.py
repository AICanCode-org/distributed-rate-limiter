"""M4: the limiter service: validate a check, ask the store, turn the decision into HTTP headers."""

from __future__ import annotations

from .errors import ApiError
from .models import Tenant, Verdict
from .store import Store

DEMO_RESOURCE = "demo"


def seconds_up(ms: int) -> int:
    # TODO(phase-3 M4): ms -> whole seconds, rounded UP (1 ms -> 1 s, 0 -> 0).
    raise NotImplementedError("TODO(phase-3 M4): seconds_up")


class LimiterService:
    def __init__(self, store: Store):
        self.store = store

    def check(self, tenant: Tenant, payload: object) -> Verdict:
        """POST /limits/check: the decision API other services call before doing the work."""
        # TODO(phase-3 M4): POST /limits/check body: missing/null -> {}; otherwise an object with
        #   optional resource and cost (validation); then decide().
        raise NotImplementedError("TODO(phase-3 M4): LimiterService.check")

    def decide(self, tenant: Tenant, resource: str, cost: int) -> Verdict:
        # TODO(phase-3 M4): cost above the tenant's limit -> 422 cost-exceeds-limit (it could never
        #   be allowed); ask the store; StoreUnavailable -> 503 limiter-unavailable (fail CLOSED,
        #   ADR-0004).
        raise NotImplementedError("TODO(phase-3 M4): LimiterService.decide")

    def enforce(self, tenant: Tenant, resource: str = DEMO_RESOURCE) -> Verdict:
        """Middleware style: a denied request becomes a 429 problem with Retry-After."""
        # TODO(phase-3 M4): decide(tenant, resource, cost 1); denied -> raise too_many(verdict).
        raise NotImplementedError("TODO(phase-3 M4): LimiterService.enforce")

    @staticmethod
    def too_many(verdict: Verdict) -> ApiError:
        # TODO(phase-3 M4): 429 rate-limited with the X-RateLimit-* headers and Retry-After = max(1,
        #   seconds_up(retryAfterMs)).
        raise NotImplementedError("TODO(phase-3 M4): LimiterService.too_many")


def rate_limit_headers(verdict: Verdict) -> dict[str, str]:
    # TODO(phase-3 M4): X-RateLimit-Limit (the policy limit), X-RateLimit-Remaining, X-RateLimit-
    #   Reset (seconds_up(resetAfterMs)).
    raise NotImplementedError("TODO(phase-3 M4): rate_limit_headers")
