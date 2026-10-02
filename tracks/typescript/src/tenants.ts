// M4: tenants (the admin API's business logic) and API-key authentication.
import type { Clock } from "./clock.js";
import type { Tenant, UsagePoint } from "./models.js";
import { newApiKey } from "./security.js";
import type { Store } from "./store.js";

export class TenantService {
  constructor(
    readonly store: Store,
    private readonly clock: Clock,
    private readonly newKey: () => string = newApiKey,
  ) {}

  /** Returns the tenant and its API key. The key is shown ONCE; only its hash is stored. */
  async create(payload: unknown): Promise<[Tenant, string]> {
    // TODO(phase-3 M4): validate id, name and policy; new API key; store only its hash and prefix (the key is
    //   returned ONCE); createdAt = updatedAt = the injected clock. DuplicateError -> 409 tenant-exists.
    throw new Error("TODO(phase-3 M4): TenantService.create");
  }

  list(): Promise<Tenant[]> {
    // TODO(phase-3 M4): all tenants, sorted by id.
    throw new Error("TODO(phase-3 M4): TenantService.list");
  }

  async get(tenantId: string): Promise<Tenant> {
    // TODO(phase-3 M4): an id that is not a valid tenant id is simply 404 not-found 'tenant' (never 400,
    //   never a Redis call); unknown -> 404.
    throw new Error("TODO(phase-3 M4): TenantService.get");
  }

  async setPolicy(tenantId: string, payload: unknown): Promise<Tenant> {
    // TODO(phase-3 M4): get, validate the new policy, bump updatedAt, save (the key hash does not change).
    throw new Error("TODO(phase-3 M4): TenantService.setPolicy");
  }

  /** A new key; the old one stops working at once. */
  async rotateKey(tenantId: string): Promise<[Tenant, string]> {
    // TODO(phase-3 M4): get, new key, save with the new hash/prefix (the old key stops working at once),
    //   return it ONCE.
    throw new Error("TODO(phase-3 M4): TenantService.rotateKey");
  }

  async delete(tenantId: string): Promise<void> {
    // TODO(phase-3 M4): get (404 rules) then delete_tenant.
    throw new Error("TODO(phase-3 M4): TenantService.delete");
  }

  async reset(tenantId: string): Promise<number> {
    // TODO(phase-3 M4): get (404 rules) then reset the limiter keys.
    throw new Error("TODO(phase-3 M4): TenantService.reset");
  }

  async usage(tenantId: string, minutesParam: string | undefined): Promise<[Tenant, number, UsagePoint[]]> {
    // TODO(phase-3 M4): validate minutes, get (404 rules), read the usage points.
    throw new Error("TODO(phase-3 M4): TenantService.usage");
  }

  /** X-API-Key -> tenant. Malformed keys are rejected without touching Redis. */
  async authenticate(apiKey: string | undefined): Promise<Tenant> {
    // TODO(phase-3 M4): malformed key -> 401 invalid-api-key WITHOUT touching Redis; otherwise find the
    //   tenant by the key's hash; unknown -> 401 invalid-api-key.
    throw new Error("TODO(phase-3 M4): TenantService.authenticate");
  }
}
