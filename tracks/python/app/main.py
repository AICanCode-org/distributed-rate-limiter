"""HTTP layer (FastAPI). Thin: read the request, call a service, shape the JSON, map errors."""

from __future__ import annotations

import logging
from collections.abc import Callable
from contextlib import asynccontextmanager
from dataclasses import replace
from typing import Annotated

from fastapi import Body, FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, PlainTextResponse, Response
from starlette.exceptions import HTTPException as StarletteHTTPException

from .advisor import AdvisorService, BreakerChat, LlmAdvisor, LlmClient, RuleAdvisor, stats_json, summarize
from .breaker import CircuitBreaker
from .clock import Clock, iso, iso_ms, utc_now
from .config import Settings
from .errors import ApiError, StoreUnavailable, limiter_unavailable, not_found, validation_error
from .limiter import LimiterService, rate_limit_headers
from .metrics import Metrics
from .models import Policy, Tenant, Verdict
from .observability import RequestIdMiddleware, configure_logging
from .security import AdminAuth, new_api_key, resolve_secret
from .store import GuardedStore, InMemoryStore, RedisStore, Store
from .tenants import TenantService

log = logging.getLogger("rateguard")
PROBLEM_JSON = "application/problem+json"
Auth = Annotated[str | None, Header(alias="Authorization")]
ApiKey = Annotated[str | None, Header(alias="X-API-Key")]
JsonBody = Annotated[object, Body()]


def build_advisor(settings: Settings, metrics: Metrics) -> AdvisorService:
    on_fallback = lambda: metrics.inc("rateguard_ai_fallback_total")  # noqa: E731
    if settings.ai_provider == "llm":
        client = LlmClient(settings.llm_base_url, settings.llm_model, settings.llm_api_key, settings.llm_timeout_ms)
        breaker = CircuitBreaker(
            "llm",
            threshold=3,
            cooldown_s=30,
            on_short_circuit=lambda: metrics.inc("rateguard_llm_short_circuits_total"),
        )
        return AdvisorService(RuleAdvisor(), LlmAdvisor(BreakerChat(client, breaker)), on_fallback)
    return AdvisorService(RuleAdvisor(), None, on_fallback)


def guard(store: Store, settings: Settings, metrics: Metrics, now: Callable[[], float] | None = None) -> GuardedStore:
    """v1.1: the store behind a circuit breaker (3 failures in a row -> open for REDIS_BREAKER_COOLDOWN_MS)."""
    extra = {"now": now} if now else {}
    breaker = CircuitBreaker(
        "redis",
        threshold=3,
        cooldown_s=settings.redis_breaker_cooldown_ms / 1000,
        is_failure=lambda e: isinstance(e, StoreUnavailable),
        on_short_circuit=lambda: metrics.inc("rateguard_store_short_circuits_total"),
        **extra,
    )
    return GuardedStore(store, breaker, on_error=lambda: metrics.inc("rateguard_store_errors_total"))


# ---- JSON shapes (identical in every track, see spec/openapi.yaml) ------------------------------
def policy_json(p: Policy) -> dict:
    return {"algorithm": p.algorithm, "limit": p.limit, "windowSeconds": p.window_seconds, "failMode": p.fail_mode}


def tenant_json(t: Tenant) -> dict:
    return {
        "id": t.id,
        "name": t.name,
        "policy": policy_json(t.policy),
        "keyPrefix": t.key_prefix,
        "createdAt": iso(t.created_at),
        "updatedAt": iso(t.updated_at),
    }


def verdict_json(x: Verdict) -> dict:
    d = x.decision
    return {
        "allowed": d.allowed,
        "tenantId": x.tenant.id,
        "resource": x.resource,
        "cost": x.cost,
        "algorithm": x.tenant.policy.algorithm,
        "limit": x.tenant.policy.limit,
        "remaining": d.remaining,
        "resetAfterMs": d.reset_after_ms,
        "retryAfterMs": d.retry_after_ms,
        "degraded": d.degraded,
    }


def create_app(
    settings: Settings | None = None,
    *,
    store: Store | None = None,
    clock: Clock = utc_now,
    advisor: AdvisorService | None = None,
    new_key: Callable[[], str] = new_api_key,
    metrics: Metrics | None = None,
    breaker_now: Callable[[], float] | None = None,
) -> FastAPI:
    """Real adapters by default; tests pass an in-memory store, a fake clock and a fake advisor."""
    settings = settings or Settings.from_env()
    metrics = metrics or Metrics()
    state: dict = {"metrics": metrics}

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        configure_logging()
        raw = store or RedisStore.from_url(settings.redis_url, settings.redis_timeout_ms, settings.lua_dir)
        guarded = guard(raw, settings, metrics, breaker_now)
        state.update(
            store=guarded,
            admin=AdminAuth(resolve_secret(settings.admin_token, "ADMIN_TOKEN")),
            tenants=TenantService(guarded, clock, new_key, lambda: metrics.inc("rateguard_tenants_created_total")),
            limiter=LimiterService(guarded, InMemoryStore(clock), metrics),
            advisor=advisor or build_advisor(settings, metrics),
        )
        yield

    app = FastAPI(title="RateGuard (Python track)", version="1.1.0", lifespan=lifespan)
    app.state.services = state  # tests reach the breaker and the metrics through this
    app.add_middleware(RequestIdMiddleware)

    def tenants() -> TenantService:
        return state["tenants"]

    def admin(authorization: str | None) -> TenantService:
        state["admin"].require(authorization)
        return tenants()

    # ---- error mapping ---------------------------------------------------
    @app.exception_handler(ApiError)
    async def api_error_handler(_req: Request, exc: ApiError):
        return JSONResponse(exc.to_problem(), status_code=exc.status, media_type=PROBLEM_JSON, headers=exc.headers)

    @app.exception_handler(StoreUnavailable)
    async def store_error_handler(_req: Request, exc: StoreUnavailable):
        log.warning("store unavailable: %s", exc)
        error = limiter_unavailable()
        return JSONResponse(error.to_problem(), status_code=503, media_type=PROBLEM_JSON, headers=error.headers)

    @app.exception_handler(RequestValidationError)
    async def request_validation_handler(_req: Request, _exc: RequestValidationError):
        problem = validation_error("request body must be a JSON object").to_problem()
        return JSONResponse(problem, status_code=400, media_type=PROBLEM_JSON)

    @app.exception_handler(StarletteHTTPException)
    async def http_error_handler(_req: Request, exc: StarletteHTTPException):
        # Unknown routes (and wrong methods) answer with problem+json too, never FastAPI's {"detail": ...}.
        error = (
            not_found("route") if exc.status_code == 404 else ApiError(exc.status_code, "http-error", str(exc.detail))
        )
        return JSONResponse(error.to_problem(), status_code=error.status, media_type=PROBLEM_JSON)

    @app.exception_handler(Exception)
    async def unexpected_handler(_req: Request, exc: Exception):
        log.exception("unhandled error", exc_info=exc)
        problem = ApiError(500, "internal-error", "Internal server error").to_problem()
        return JSONResponse(problem, status_code=500, media_type=PROBLEM_JSON)

    # ---- ops -----------------------------------------------------------------
    @app.get("/health")
    def health():
        # v1.1: without Redis the app still answers (from each tenant's failMode): DEGRADED, not down.
        up = state["store"].ping()
        body = {"status": "UP" if up else "DEGRADED", "checks": {"redis": "UP" if up else "DOWN"}}
        return JSONResponse(body, status_code=200)

    @app.get("/metrics")
    def metrics_endpoint():
        return PlainTextResponse(metrics.render(), media_type="text/plain; version=0.0.4")

    # ---- admin: tenants ------------------------------------------------------
    @app.post("/api/v1/admin/tenants", status_code=201)
    def create_tenant(payload: JsonBody, authorization: Auth = None):
        tenant, api_key = admin(authorization).create(payload)
        headers = {"Location": f"/api/v1/admin/tenants/{tenant.id}"}
        return JSONResponse({**tenant_json(tenant), "apiKey": api_key}, status_code=201, headers=headers)

    @app.get("/api/v1/admin/tenants")
    def list_tenants(authorization: Auth = None):
        return {"items": [tenant_json(t) for t in admin(authorization).list()]}

    @app.get("/api/v1/admin/tenants/{tenant_id}")
    def get_tenant(tenant_id: str, authorization: Auth = None):
        return tenant_json(admin(authorization).get(tenant_id))

    @app.delete("/api/v1/admin/tenants/{tenant_id}", status_code=204)
    def delete_tenant(tenant_id: str, authorization: Auth = None):
        admin(authorization).delete(tenant_id)
        return Response(status_code=204)

    @app.put("/api/v1/admin/tenants/{tenant_id}/policy")
    def set_policy(tenant_id: str, payload: JsonBody, authorization: Auth = None):
        return tenant_json(admin(authorization).set_policy(tenant_id, payload))

    @app.post("/api/v1/admin/tenants/{tenant_id}/rotate-key")
    def rotate_key(tenant_id: str, authorization: Auth = None):
        tenant, api_key = admin(authorization).rotate_key(tenant_id)
        return {**tenant_json(tenant), "apiKey": api_key}

    @app.post("/api/v1/admin/tenants/{tenant_id}/reset", status_code=204)
    def reset(tenant_id: str, authorization: Auth = None):
        admin(authorization).reset(tenant_id)
        return Response(status_code=204)

    @app.get("/api/v1/admin/tenants/{tenant_id}/usage")
    def usage(tenant_id: str, minutes: str | None = None, authorization: Auth = None):
        tenant, n, points = admin(authorization).usage(tenant_id, minutes)
        return {
            "tenantId": tenant.id,
            "minutes": n,
            "totals": {"allowed": sum(p.allowed for p in points), "denied": sum(p.denied for p in points)},
            "series": [{"minute": iso_ms(p.minute * 60_000), "allowed": p.allowed, "denied": p.denied} for p in points],
        }

    @app.post("/api/v1/admin/tenants/{tenant_id}/advice")
    def advice(tenant_id: str, minutes: str | None = None, authorization: Auth = None):
        tenant, n, points = admin(authorization).usage(tenant_id, minutes)
        stats = summarize(tenant.policy, points)
        result = state["advisor"].advise(tenant, stats)
        a = result.advice
        suggested = replace(tenant.policy, limit=a.limit) if a.limit is not None else None
        return {
            "tenantId": tenant.id,
            "provider": result.provider,
            "fallback": result.fallback,
            "currentPolicy": policy_json(tenant.policy),
            "stats": stats_json(stats),
            "action": a.action,
            "suggestedPolicy": policy_json(suggested) if suggested else None,
            "summary": a.summary,
            "generatedAt": iso(clock()),
        }

    # ---- tenants: the limiter -----------------------------------------------
    @app.post("/api/v1/limits/check")
    def check(api_key: ApiKey = None, payload: Annotated[object, Body()] = None):
        verdict = state["limiter"].check(tenants().authenticate(api_key), payload)
        return JSONResponse(verdict_json(verdict), headers=rate_limit_headers(verdict))

    @app.get("/api/v1/demo/hello")
    def hello(api_key: ApiKey = None):
        tenant = tenants().authenticate(api_key)
        verdict = state["limiter"].enforce(tenant)
        body = {"message": f"Hello, {tenant.name}!", "tenantId": tenant.id}
        return JSONResponse(body, headers=rate_limit_headers(verdict))

    return app
