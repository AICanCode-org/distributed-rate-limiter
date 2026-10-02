package org.aicancode.rateguard.config;

import java.nio.file.Path;
import java.time.Clock;
import java.util.function.LongSupplier;
import org.aicancode.rateguard.ai.AdvisorService;
import org.aicancode.rateguard.ai.BreakerLlmClient;
import org.aicancode.rateguard.ai.HttpLlmClient;
import org.aicancode.rateguard.ai.LlmAdvisor;
import org.aicancode.rateguard.ai.RuleAdvisor;
import org.aicancode.rateguard.domain.StoreUnavailableException;
import org.aicancode.rateguard.metrics.Metrics;
import org.aicancode.rateguard.resilience.CircuitBreaker;
import org.aicancode.rateguard.security.AdminAuth;
import org.aicancode.rateguard.security.ApiKeys;
import org.aicancode.rateguard.service.LimiterService;
import org.aicancode.rateguard.service.TenantService;
import org.aicancode.rateguard.store.GuardedStore;
import org.aicancode.rateguard.store.InMemoryStore;
import org.aicancode.rateguard.store.RedisStore;
import org.aicancode.rateguard.store.Store;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Wires real adapters. Unit tests build the same services by hand on the in-memory store. */
@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public Metrics metrics() {
        return new Metrics();
    }

    @Bean(destroyMethod = "close")
    public RedisStore redisStore(AppProperties props) {
        return RedisStore.fromUrl(props.redisUrl(), props.redisTimeoutMs(), Path.of(props.luaDir()));
    }

    /** v1.1: the store behind a circuit breaker (3 failures in a row -> open for REDIS_BREAKER_COOLDOWN_MS). */
    public static GuardedStore guard(Store store, long cooldownMs, Metrics metrics, LongSupplier nowMs) {
        CircuitBreaker breaker = new CircuitBreaker("redis", 3, cooldownMs, nowMs,
                e -> e instanceof StoreUnavailableException, () -> metrics.inc(Metrics.STORE_SHORT_CIRCUITS));
        return new GuardedStore(store, breaker, () -> metrics.inc(Metrics.STORE_ERRORS));
    }

    @Bean
    @Primary
    public Store store(RedisStore redis, AppProperties props, Metrics metrics) {
        return guard(redis, props.redisBreakerCooldownMs(), metrics, System::currentTimeMillis);
    }

    @Bean
    public AdminAuth adminAuth(AppProperties props) {
        return new AdminAuth(ApiKeys.resolveSecret(props.adminToken(), "ADMIN_TOKEN"));
    }

    @Bean
    public TenantService tenantService(Store store, Clock clock, Metrics metrics) {
        return new TenantService(store, clock, ApiKeys::newApiKey, () -> metrics.inc(Metrics.TENANTS_CREATED));
    }

    @Bean
    public LimiterService limiterService(Store store, Clock clock, Metrics metrics) {
        return new LimiterService(store, new InMemoryStore(clock), metrics);
    }

    @Bean
    public AdvisorService advisorService(AppProperties props, Metrics metrics) {
        return advisor(props, metrics);
    }

    public static AdvisorService advisor(AppProperties props, Metrics metrics) {
        LlmAdvisor llm = null;
        if (props.llmEnabled()) {
            CircuitBreaker breaker = new CircuitBreaker("llm", 3, 30_000, System::currentTimeMillis, e -> true,
                    () -> metrics.inc(Metrics.LLM_SHORT_CIRCUITS));
            llm = new LlmAdvisor(new BreakerLlmClient(new HttpLlmClient(props.llmBaseUrl(), props.llmModel(),
                    props.llmApiKey(), props.llmTimeoutMs()), breaker));
        }
        return new AdvisorService(new RuleAdvisor(), llm, () -> metrics.inc(Metrics.AI_FALLBACK));
    }
}
