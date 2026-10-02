from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from app.advisor import AdvisorService, RuleAdvisor
from app.config import Settings
from app.main import create_app
from app.store import InMemoryStore

# ---------------------------------------------------------------------------
# PENDING tests describe behaviour you have not built yet, so they are skipped
# (reported as "s" by pytest). When you start a milestone, delete its line here,
# watch the tests fail (red), implement until they pass (green). See docs/phase-3.
# ---------------------------------------------------------------------------
PENDING: dict[str, str] = {}


def pytest_collection_modifyitems(config, items):
    for item in items:
        reason = PENDING.get(item.path.name)
        if reason:
            item.add_marker(pytest.mark.skip(reason=f"PENDING ({reason}) - see tests/conftest.py"))


ADMIN_TOKEN = "test-admin-token-at-least-32-characters"


class FakeClock:
    """A clock you can move. Tests should never depend on the real time."""

    def __init__(self, start: datetime | None = None):
        self.now = start or datetime(2026, 10, 2, 9, 30, tzinfo=UTC)

    def __call__(self) -> datetime:
        return self.now

    def advance(self, **kwargs):
        self.now = self.now + timedelta(**kwargs)


class KeyGen:
    """Predictable API keys: rg_000...001, rg_000...002, ..."""

    def __init__(self):
        self.n = 0

    def __call__(self) -> str:
        self.n += 1
        return f"rg_{self.n:032x}"


def test_settings(**overrides) -> Settings:
    base = Settings.from_env()
    return Settings(**{**base.__dict__, "admin_token": ADMIN_TOKEN, "ai_provider": "offline", **overrides})


test_settings.__test__ = False  # not a test, despite the name


@pytest.fixture
def clock() -> FakeClock:
    return FakeClock()


@pytest.fixture
def store(clock) -> InMemoryStore:
    return InMemoryStore(clock)


def make_client(store, clock, advisor=None, **kwargs):
    settings = kwargs.pop("settings", None) or test_settings()
    app = create_app(
        settings, store=store, clock=clock, advisor=advisor or AdvisorService(RuleAdvisor()), new_key=KeyGen(), **kwargs
    )
    return TestClient(app)


@pytest.fixture
def client(store, clock):
    """The real FastAPI app on the in-memory store (no Redis, no network)."""
    with make_client(store, clock) as c:
        yield c


ADMIN = {"Authorization": f"Bearer {ADMIN_TOKEN}"}


class Api:
    """Small helpers so API tests read like the user stories."""

    def __init__(self, client: TestClient):
        self.client = client

    def tenant(
        self,
        tenant_id: str = "acme",
        algorithm: str = "TOKEN_BUCKET",
        limit: int = 5,
        window: int = 60,
        name: str = "Acme Corp",
        fail_mode: str | None = None,
    ) -> dict:
        policy: dict = {"algorithm": algorithm, "limit": limit, "windowSeconds": window}
        if fail_mode:
            policy["failMode"] = fail_mode
        body = {"id": tenant_id, "name": name, "policy": policy}
        r = self.client.post("/api/v1/admin/tenants", json=body, headers=ADMIN)
        assert r.status_code == 201, r.text
        return r.json()

    def check(self, api_key: str, **body):
        return self.client.post("/api/v1/limits/check", json=body or None, headers={"X-API-Key": api_key})

    def hello(self, api_key: str):
        return self.client.get("/api/v1/demo/hello", headers={"X-API-Key": api_key})


@pytest.fixture
def api(client) -> Api:
    return Api(client)
