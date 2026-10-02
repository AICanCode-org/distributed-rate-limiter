// Builds the services from settings. Shared by the process entrypoint and the tests.
import { AdvisorService, BreakerChat, LlmAdvisor, LlmClient, RuleAdvisor } from "./advisor.js";
import type { AppDeps } from "./app.js";
import { CircuitBreaker } from "./breaker.js";
import { systemClock, type Clock } from "./clock.js";
import type { Settings } from "./config.js";
import { StoreUnavailable } from "./errors.js";
import { LimiterService } from "./limiter.js";
import { Metrics } from "./metrics.js";
import { AdminAuth, newApiKey, resolveSecret } from "./security.js";
import { GuardedStore, InMemoryStore, type Store } from "./store.js";
import { TenantService } from "./tenants.js";

export function buildAdvisor(
  settings: Settings,
  warn: (msg: string) => void = console.warn,
  metrics: Metrics = new Metrics(),
): AdvisorService {
  if (settings.aiProvider === "llm") {
    const client = new LlmClient(
      settings.llmBaseUrl,
      settings.llmModel,
      settings.llmApiKey,
      settings.llmTimeoutMs,
    );
    const breaker = new CircuitBreaker("llm", {
      cooldownMs: 30_000,
      onShortCircuit: () => metrics.inc("rateguard_llm_short_circuits_total"),
      warn,
    });
    return new AdvisorService(new RuleAdvisor(), new LlmAdvisor(new BreakerChat(client, breaker)), warn);
  }
  return new AdvisorService(new RuleAdvisor(), null, warn);
}

/** v1.1: the store behind a circuit breaker (3 failures in a row -> open for REDIS_BREAKER_COOLDOWN_MS). */
export function guard(
  store: Store,
  settings: Settings,
  metrics: Metrics,
  warn: (msg: string) => void,
  now?: () => number,
): GuardedStore {
  const breaker = new CircuitBreaker("redis", {
    threshold: 3,
    cooldownMs: settings.redisBreakerCooldownMs,
    isFailure: (err) => err instanceof StoreUnavailable,
    onShortCircuit: () => metrics.inc("rateguard_store_short_circuits_total"),
    warn,
    ...(now ? { now } : {}),
  });
  return new GuardedStore(store, breaker, () => metrics.inc("rateguard_store_errors_total"));
}

export interface WireOptions {
  clock?: Clock;
  advisor?: AdvisorService;
  newKey?: () => string;
  warn?: (msg: string) => void;
  metrics?: Metrics;
  /** the breaker's clock (tests move it to skip the cooldown) */
  breakerNow?: () => number;
}

export function wire(settings: Settings, store: Store, opts: WireOptions = {}): AppDeps {
  const clock = opts.clock ?? systemClock;
  const warn = opts.warn ?? ((msg: string) => console.warn(msg));
  const metrics = opts.metrics ?? new Metrics();
  const guarded = guard(store, settings, metrics, warn, opts.breakerNow);
  return {
    store: guarded,
    admin: new AdminAuth(resolveSecret(settings.adminToken, warn, "ADMIN_TOKEN")),
    tenants: new TenantService(guarded, clock, opts.newKey ?? newApiKey, () =>
      metrics.inc("rateguard_tenants_created_total"),
    ),
    limiter: new LimiterService(guarded, new InMemoryStore(clock), metrics),
    advisor: opts.advisor ?? buildAdvisor(settings, warn, metrics),
    clock,
    warn,
    metrics,
  };
}
