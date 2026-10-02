// Builds the services from settings. Shared by the process entrypoint and the tests.
import { AdvisorService, LlmAdvisor, LlmClient, RuleAdvisor } from "./advisor.js";
import type { AppDeps } from "./app.js";
import { systemClock, type Clock } from "./clock.js";
import type { Settings } from "./config.js";
import { LimiterService } from "./limiter.js";
import { AdminAuth, newApiKey, resolveSecret } from "./security.js";
import type { Store } from "./store.js";
import { TenantService } from "./tenants.js";

export function buildAdvisor(settings: Settings, warn: (msg: string) => void = console.warn): AdvisorService {
  if (settings.aiProvider === "llm") {
    const client = new LlmClient(
      settings.llmBaseUrl,
      settings.llmModel,
      settings.llmApiKey,
      settings.llmTimeoutMs,
    );
    return new AdvisorService(new RuleAdvisor(), new LlmAdvisor(client), warn);
  }
  return new AdvisorService(new RuleAdvisor(), null, warn);
}

export interface WireOptions {
  clock?: Clock;
  advisor?: AdvisorService;
  newKey?: () => string;
  warn?: (msg: string) => void;
}

export function wire(settings: Settings, store: Store, opts: WireOptions = {}): AppDeps {
  const clock = opts.clock ?? systemClock;
  const warn = opts.warn ?? ((msg: string) => console.warn(msg));
  return {
    store,
    admin: new AdminAuth(resolveSecret(settings.adminToken, warn, "ADMIN_TOKEN")),
    tenants: new TenantService(store, clock, opts.newKey ?? newApiKey),
    limiter: new LimiterService(store),
    advisor: opts.advisor ?? buildAdvisor(settings, warn),
    clock,
    warn,
  };
}
