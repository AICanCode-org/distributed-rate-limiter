"""Domain errors that map 1:1 to RFC 9457 problem responses (identical in every track)."""

from __future__ import annotations

PROBLEM_BASE = "https://rateguard.example/problems/"


class ApiError(Exception):
    def __init__(self, status: int, slug: str, title: str, detail: str | None = None, headers: dict | None = None):
        super().__init__(detail or title)
        self.status = status
        self.slug = slug
        self.title = title
        self.detail = detail
        self.headers = headers or {}

    def to_problem(self) -> dict:
        body = {"type": PROBLEM_BASE + self.slug, "title": self.title, "status": self.status}
        if self.detail:
            body["detail"] = self.detail
        return body


def validation_error(detail: str) -> ApiError:
    return ApiError(400, "validation-error", "Validation failed", detail)


def unauthorized() -> ApiError:
    return ApiError(401, "unauthorized", "Unauthorized", "admin token required", {"WWW-Authenticate": "Bearer"})


def invalid_api_key() -> ApiError:
    return ApiError(401, "invalid-api-key", "Invalid API key", "a valid X-API-Key header is required")


def not_found(what: str) -> ApiError:
    return ApiError(404, "not-found", "Not found", f"{what} not found")


def tenant_exists() -> ApiError:
    return ApiError(409, "tenant-exists", "Tenant already exists", "a tenant with this id exists")


def cost_exceeds_limit(cost: int, limit: int) -> ApiError:
    return ApiError(422, "cost-exceeds-limit", "Cost exceeds limit", f"cost {cost} is more than the limit of {limit}")


def rate_limited(limit: int, window_seconds: int, retry_after_s: int, headers: dict) -> ApiError:
    detail = f"limit of {limit} per {window_seconds} s reached, retry in {retry_after_s} s"
    return ApiError(429, "rate-limited", "Too many requests", detail, headers)


def limiter_unavailable() -> ApiError:
    return ApiError(
        503, "limiter-unavailable", "Limiter unavailable", "the rate-limit store is unavailable", {"Retry-After": "1"}
    )


class DuplicateError(Exception):
    """Raised by a store when a tenant id is already taken (SADD said 0: Redis decided the race)."""


class StoreUnavailable(Exception):
    """Raised by a store when Redis cannot answer (refused, timed out, broken connection)."""
