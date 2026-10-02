"""Hand-rolled Prometheus counters (text exposition format 0.0.4). v1.1.

No client library on purpose: students see that /metrics is just text. The metric names are
identical in all three tracks (see spec/openapi.yaml, GET /metrics).
"""

from __future__ import annotations

import threading

COUNTERS: dict[str, str] = {
    "rateguard_decisions_allowed_total": "Limiter checks that were allowed.",
    "rateguard_decisions_denied_total": "Limiter checks that were denied.",
    "rateguard_degraded_decisions_total": "Checks decided by the tenant's failMode because Redis was unavailable.",
    "rateguard_store_errors_total": "Redis calls that failed (refused, timed out, broken connection).",
    "rateguard_store_short_circuits_total": "Redis calls skipped because the circuit breaker was open.",
    "rateguard_tenants_created_total": "Tenants created with POST /admin/tenants.",
    "rateguard_ai_fallback_total": "Advice requests that fell back to the offline rules.",
    "rateguard_llm_short_circuits_total": "LLM calls skipped because the circuit breaker was open.",
}


class Metrics:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._values = dict.fromkeys(COUNTERS, 0)

    def inc(self, name: str, amount: int = 1) -> None:
        with self._lock:
            self._values[name] += amount

    def value(self, name: str) -> int:
        return self._values[name]

    def render(self) -> str:
        lines: list[str] = []
        for name, help_text in COUNTERS.items():
            lines += [f"# HELP {name} {help_text}", f"# TYPE {name} counter", f"{name} {self._values[name]}"]
        return "\n".join(lines) + "\n"
