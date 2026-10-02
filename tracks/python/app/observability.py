"""Request IDs + one JSON log line per request. v1.1."""

from __future__ import annotations

import json
import logging
import re
import time
import uuid
from datetime import UTC, datetime

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request

REQUEST_ID_HEADER = "X-Request-ID"
_SAFE_ID = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
access_log = logging.getLogger("rateguard.access")


class JsonFormatter(logging.Formatter):
    """Formats a record as one JSON object per line (easy to grep, easy for log tools)."""

    def format(self, record: logging.LogRecord) -> str:
        entry = {
            "ts": datetime.fromtimestamp(record.created, UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        entry.update(getattr(record, "fields", {}))
        if record.exc_info:
            entry["error"] = self.formatException(record.exc_info)
        return json.dumps(entry)


def configure_logging(level: int = logging.INFO) -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(level)


def incoming_or_new_id(value: str | None) -> str:
    """Reuse a caller's ID only if it is safe to echo into headers/logs; otherwise mint one."""
    return value if value and _SAFE_ID.match(value) else str(uuid.uuid4())


class RequestIdMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        request_id = incoming_or_new_id(request.headers.get(REQUEST_ID_HEADER))
        request.state.request_id = request_id
        start = time.perf_counter()
        response = await call_next(request)
        response.headers[REQUEST_ID_HEADER] = request_id
        access_log.info(
            "request",
            extra={
                "fields": {
                    "requestId": request_id,
                    "method": request.method,
                    "path": request.url.path,
                    "status": response.status_code,
                    "durationMs": round((time.perf_counter() - start) * 1000, 2),
                }
            },
        )
        return response
