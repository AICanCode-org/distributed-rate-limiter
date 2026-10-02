"""Configuration from environment variables (12-factor). Same names in every track."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _default_lua_dir() -> str:
    """tracks/python/app/config.py -> <repo>/spec/lua (only used outside Docker)."""
    parents = Path(__file__).resolve().parents
    return str(parents[3] / "spec" / "lua") if len(parents) > 3 else "lua"


@dataclass(frozen=True)
class Settings:
    port: int
    redis_url: str
    redis_timeout_ms: int
    redis_breaker_cooldown_ms: int  # v1.1: how long the Redis circuit stays open before one trial call
    lua_dir: str
    admin_token: str | None  # None -> a random per-process token (the admin API is locked, logged as a warning)
    ai_provider: str  # "offline" | "llm"
    llm_base_url: str
    llm_api_key: str | None
    llm_model: str
    llm_timeout_ms: int

    @staticmethod
    def from_env() -> Settings:
        return Settings(
            port=int(os.getenv("PORT", "8080")),
            redis_url=os.getenv("REDIS_URL", "redis://localhost:6379/0"),
            redis_timeout_ms=int(os.getenv("REDIS_TIMEOUT_MS", "1000")),
            redis_breaker_cooldown_ms=int(os.getenv("REDIS_BREAKER_COOLDOWN_MS", "5000")),
            lua_dir=os.getenv("LUA_DIR") or _default_lua_dir(),
            admin_token=os.getenv("ADMIN_TOKEN") or None,
            ai_provider=os.getenv("AI_PROVIDER", "offline").lower(),
            llm_base_url=os.getenv("LLM_BASE_URL", "http://localhost:11434/v1").rstrip("/"),
            llm_api_key=os.getenv("LLM_API_KEY") or None,
            llm_model=os.getenv("LLM_MODEL", "llama3.2:1b"),
            llm_timeout_ms=int(os.getenv("LLM_TIMEOUT_MS", "5000")),
        )
