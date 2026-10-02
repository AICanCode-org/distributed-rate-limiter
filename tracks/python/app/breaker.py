"""v1.1 (after the game day): a circuit breaker, used in front of Redis and in front of the LLM.

CLOSED: calls go through; ``threshold`` failures in a row open the circuit.
OPEN: calls fail immediately with CircuitOpenError (no timeout to wait for) for ``cooldown_s`` seconds.
HALF-OPEN: after the cooldown ONE trial call goes through; success closes the circuit, failure re-opens it.
"""

from __future__ import annotations

import logging
import threading
import time
from collections.abc import Callable
from typing import TypeVar

T = TypeVar("T")
log = logging.getLogger("rateguard.breaker")


class CircuitOpenError(Exception):
    """The breaker is open: the call was skipped."""


class CircuitBreaker:
    def __init__(
        self,
        name: str,
        threshold: int = 3,
        cooldown_s: float = 5.0,
        now: Callable[[], float] = time.monotonic,
        is_failure: Callable[[Exception], bool] = lambda _e: True,
        on_short_circuit: Callable[[], None] = lambda: None,
    ):
        self.name = name
        self._threshold = threshold
        self._cooldown_s = cooldown_s
        self._now = now
        self._is_failure = is_failure
        self._on_short_circuit = on_short_circuit
        self._lock = threading.Lock()
        self._failures = 0
        self._opened_at: float | None = None
        self._trial_running = False

    @property
    def state(self) -> str:
        if self._opened_at is None:
            return "closed"
        return "half-open" if self._now() - self._opened_at >= self._cooldown_s else "open"

    def call(self, fn: Callable[[], T]) -> T:
        with self._lock:
            state = self.state
            if state == "open" or (state == "half-open" and self._trial_running):
                self._on_short_circuit()
                raise CircuitOpenError(f"{self.name} circuit open: skipping the call")
            self._trial_running = state == "half-open"
        try:
            result = fn()
        except Exception as exc:
            with self._lock:
                self._trial_running = False
                if self._is_failure(exc):
                    self._failures += 1
                    if self._opened_at is not None or self._failures >= self._threshold:
                        self._opened_at = self._now()
                        log.warning("%s circuit opened after %d failure(s)", self.name, self._failures)
                else:  # the dependency answered (e.g. "duplicate id"): it is healthy
                    self._close()
            raise
        with self._lock:
            self._trial_running = False
            self._close()
        return result

    def _close(self) -> None:
        if self._opened_at is not None:
            log.info("%s circuit closed again", self.name)
        self._failures = 0
        self._opened_at = None
