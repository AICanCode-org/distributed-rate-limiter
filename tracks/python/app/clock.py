"""Time. Services never call datetime.now() directly: they get a clock, so tests can travel in time."""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime

Clock = Callable[[], datetime]


def utc_now() -> datetime:
    now = datetime.now(UTC)
    return now.replace(microsecond=now.microsecond // 1000 * 1000)


def to_ms(dt: datetime) -> int:
    """Epoch milliseconds (what the limiter algorithms and the Lua scripts work in)."""
    return int(dt.timestamp() * 1000)


def iso(dt: datetime | None) -> str | None:
    """ISO-8601 UTC with millisecond precision and a 'Z' suffix, identical in every track."""
    if dt is None:
        return None
    return dt.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def iso_ms(ms: int) -> str:
    return iso(datetime.fromtimestamp(ms / 1000, UTC))  # type: ignore[return-value]
