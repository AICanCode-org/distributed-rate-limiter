"""Plain data. No framework imports, no I/O."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

TOKEN_BUCKET = "TOKEN_BUCKET"
SLIDING_WINDOW = "SLIDING_WINDOW"
ALGORITHMS = (TOKEN_BUCKET, SLIDING_WINDOW)
# v1.1: what a check does while Redis is unavailable (ADR-0006). CLOSED is the v1.0 behaviour.
CLOSED, OPEN, LOCAL = "CLOSED", "OPEN", "LOCAL"
FAIL_MODES = (CLOSED, OPEN, LOCAL)


@dataclass(frozen=True)
class Policy:
    algorithm: str
    limit: int
    window_seconds: int
    fail_mode: str = CLOSED

    @property
    def window_ms(self) -> int:
        return self.window_seconds * 1000


@dataclass(frozen=True)
class Tenant:
    id: str
    name: str
    policy: Policy
    key_hash: str
    key_prefix: str
    created_at: datetime
    updated_at: datetime


@dataclass(frozen=True)
class Decision:
    """The answer of one limiter call (identical fields to the Lua scripts' return value)."""

    allowed: bool
    remaining: int
    retry_after_ms: int
    reset_after_ms: int
    degraded: bool = False  # v1.1: decided by the failMode because Redis was unavailable


@dataclass(frozen=True)
class Verdict:
    """A decision plus what it was about (what the HTTP layer renders)."""

    tenant: Tenant
    resource: str
    cost: int
    decision: Decision


@dataclass(frozen=True)
class UsagePoint:
    minute: int  # epoch minute (ms // 60000)
    allowed: int
    denied: int
