"""M4: tenants (the admin API's business logic) and API-key authentication."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import replace

from . import validation as v
from .clock import Clock
from .errors import DuplicateError, invalid_api_key, not_found, tenant_exists
from .models import Tenant, UsagePoint
from .security import hash_api_key, key_prefix, looks_like_api_key, new_api_key
from .store import Store


class TenantService:
    def __init__(self, store: Store, clock: Clock, new_key: Callable[[], str] = new_api_key):
        self.store = store
        self._clock = clock
        self._new_key = new_key

    def create(self, payload: object) -> tuple[Tenant, str]:
        """Returns the tenant and its API key. The key is shown ONCE; only its hash is stored."""
        body = v.require_object(payload)
        tenant_id = v.tenant_id(body.get("id"))
        name = v.tenant_name(body.get("name"))
        policy = v.policy(body.get("policy"))
        api_key = self._new_key()
        now = self._clock()
        tenant = Tenant(tenant_id, name, policy, hash_api_key(api_key), key_prefix(api_key), now, now)
        try:
            self.store.create_tenant(tenant)
        except DuplicateError:
            raise tenant_exists() from None
        return tenant, api_key

    def list(self) -> list[Tenant]:
        return self.store.list_tenants()

    def get(self, tenant_id: str) -> Tenant:
        # An id that cannot exist is simply not found (never a 400, never a Redis call).
        tenant = self.store.get_tenant(tenant_id) if v.is_tenant_id(tenant_id) else None
        if tenant is None:
            raise not_found("tenant")
        return tenant

    def set_policy(self, tenant_id: str, payload: object) -> Tenant:
        tenant = self.get(tenant_id)
        updated = replace(tenant, policy=v.policy(payload), updated_at=self._clock())
        self.store.save_tenant(updated, tenant.key_hash)
        return updated

    def rotate_key(self, tenant_id: str) -> tuple[Tenant, str]:
        """A new key; the old one stops working at once."""
        tenant = self.get(tenant_id)
        api_key = self._new_key()
        updated = replace(
            tenant, key_hash=hash_api_key(api_key), key_prefix=key_prefix(api_key), updated_at=self._clock()
        )
        self.store.save_tenant(updated, tenant.key_hash)
        return updated, api_key

    def delete(self, tenant_id: str) -> None:
        self.store.delete_tenant(self.get(tenant_id))

    def reset(self, tenant_id: str) -> int:
        return self.store.reset(self.get(tenant_id).id)

    def usage(self, tenant_id: str, minutes_param: str | None) -> tuple[Tenant, int, list[UsagePoint]]:
        minutes = v.minutes(minutes_param)
        tenant = self.get(tenant_id)
        return tenant, minutes, self.store.usage(tenant.id, minutes)

    def authenticate(self, api_key: str | None) -> Tenant:
        """X-API-Key -> tenant. Malformed keys are rejected without touching Redis."""
        if not looks_like_api_key(api_key):
            raise invalid_api_key()
        tenant = self.store.find_by_key_hash(hash_api_key(api_key))  # type: ignore[arg-type]
        if tenant is None:
            raise invalid_api_key()
        return tenant
