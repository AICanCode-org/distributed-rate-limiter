// M4: tenants (the admin API's business logic) and API-key authentication.
import type { Clock } from "./clock.js";
import { DuplicateError, invalidApiKey, notFound, tenantExists } from "./errors.js";
import type { Tenant, UsagePoint } from "./models.js";
import { hashApiKey, keyPrefix, looksLikeApiKey, newApiKey } from "./security.js";
import type { Store } from "./store.js";
import * as v from "./validation.js";

export class TenantService {
  constructor(
    readonly store: Store,
    private readonly clock: Clock,
    private readonly newKey: () => string = newApiKey,
  ) {}

  /** Returns the tenant and its API key. The key is shown ONCE; only its hash is stored. */
  async create(payload: unknown): Promise<[Tenant, string]> {
    const body = v.requireObject(payload);
    const id = v.tenantId(body.id);
    const name = v.tenantName(body.name);
    const policy = v.policy(body.policy);
    const apiKey = this.newKey();
    const now = this.clock();
    const tenant: Tenant = {
      id,
      name,
      policy,
      keyHash: hashApiKey(apiKey),
      keyPrefix: keyPrefix(apiKey),
      createdAt: now,
      updatedAt: now,
    };
    try {
      await this.store.createTenant(tenant);
    } catch (err) {
      if (err instanceof DuplicateError) throw tenantExists();
      throw err;
    }
    return [tenant, apiKey];
  }

  list(): Promise<Tenant[]> {
    return this.store.listTenants();
  }

  async get(tenantId: string): Promise<Tenant> {
    // An id that cannot exist is simply not found (never a 400, never a Redis call).
    const tenant = v.isTenantId(tenantId) ? await this.store.getTenant(tenantId) : null;
    if (tenant === null) throw notFound("tenant");
    return tenant;
  }

  async setPolicy(tenantId: string, payload: unknown): Promise<Tenant> {
    const tenant = await this.get(tenantId);
    const updated: Tenant = { ...tenant, policy: v.policy(payload), updatedAt: this.clock() };
    await this.store.saveTenant(updated, tenant.keyHash);
    return updated;
  }

  /** A new key; the old one stops working at once. */
  async rotateKey(tenantId: string): Promise<[Tenant, string]> {
    const tenant = await this.get(tenantId);
    const apiKey = this.newKey();
    const updated: Tenant = {
      ...tenant,
      keyHash: hashApiKey(apiKey),
      keyPrefix: keyPrefix(apiKey),
      updatedAt: this.clock(),
    };
    await this.store.saveTenant(updated, tenant.keyHash);
    return [updated, apiKey];
  }

  async delete(tenantId: string): Promise<void> {
    await this.store.deleteTenant(await this.get(tenantId));
  }

  async reset(tenantId: string): Promise<number> {
    return this.store.reset((await this.get(tenantId)).id);
  }

  async usage(tenantId: string, minutesParam: string | undefined): Promise<[Tenant, number, UsagePoint[]]> {
    const minutes = v.minutes(minutesParam);
    const tenant = await this.get(tenantId);
    return [tenant, minutes, await this.store.usage(tenant.id, minutes)];
  }

  /** X-API-Key -> tenant. Malformed keys are rejected without touching Redis. */
  async authenticate(apiKey: string | undefined): Promise<Tenant> {
    if (!looksLikeApiKey(apiKey)) throw invalidApiKey();
    const tenant = await this.store.findByKeyHash(hashApiKey(apiKey));
    if (tenant === null) throw invalidApiKey();
    return tenant;
  }
}
