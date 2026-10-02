"""M4: tenants (the admin API's business logic) and API-key authentication."""

from __future__ import annotations

from collections.abc import Callable

from .clock import Clock
from .models import Tenant, UsagePoint
from .security import new_api_key
from .store import Store


class TenantService:
    def __init__(self, store: Store, clock: Clock, new_key: Callable[[], str] = new_api_key):
        self.store = store
        self._clock = clock
        self._new_key = new_key

    def create(self, payload: object) -> tuple[Tenant, str]:
        """Returns the tenant and its API key. The key is shown ONCE; only its hash is stored."""
        # TODO(phase-3 M4): validate id, name and policy; new API key; store only its hash and
        #   prefix (the key is returned ONCE); createdAt = updatedAt = the injected clock.
        #   DuplicateError -> 409 tenant-exists.
        raise NotImplementedError("TODO(phase-3 M4): TenantService.create")

    def list(self) -> list[Tenant]:
        # TODO(phase-3 M4): all tenants, sorted by id.
        raise NotImplementedError("TODO(phase-3 M4): TenantService.list")

    def get(self, tenant_id: str) -> Tenant:
        # An id that cannot exist is simply not found (never a 400, never a Redis call).
        # TODO(phase-3 M4): an id that is not a valid tenant id is simply 404 not-found 'tenant'
        #   (never 400, never a Redis call); unknown -> 404.
        raise NotImplementedError("TODO(phase-3 M4): TenantService.get")

    def set_policy(self, tenant_id: str, payload: object) -> Tenant:
        # TODO(phase-3 M4): get, validate the new policy, bump updatedAt, save (the key hash does
        #   not change).
        raise NotImplementedError("TODO(phase-3 M4): TenantService.set_policy")

    def rotate_key(self, tenant_id: str) -> tuple[Tenant, str]:
        """A new key; the old one stops working at once."""
        # TODO(phase-3 M4): get, new key, save with the new hash/prefix (the old key stops working
        #   at once), return it ONCE.
        raise NotImplementedError("TODO(phase-3 M4): TenantService.rotate_key")

    def delete(self, tenant_id: str) -> None:
        # TODO(phase-3 M4): get (404 rules) then delete_tenant.
        raise NotImplementedError("TODO(phase-3 M4): TenantService.delete")

    def reset(self, tenant_id: str) -> int:
        # TODO(phase-3 M4): get (404 rules) then reset the limiter keys.
        raise NotImplementedError("TODO(phase-3 M4): TenantService.reset")

    def usage(self, tenant_id: str, minutes_param: str | None) -> tuple[Tenant, int, list[UsagePoint]]:
        # TODO(phase-3 M4): validate minutes, get (404 rules), read the usage points.
        raise NotImplementedError("TODO(phase-3 M4): TenantService.usage")

    def authenticate(self, api_key: str | None) -> Tenant:
        """X-API-Key -> tenant. Malformed keys are rejected without touching Redis."""
        # TODO(phase-3 M4): malformed key -> 401 invalid-api-key WITHOUT touching Redis; otherwise
        #   find the tenant by the key's hash; unknown -> 401 invalid-api-key.
        raise NotImplementedError("TODO(phase-3 M4): TenantService.authenticate")
