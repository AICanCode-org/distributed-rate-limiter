package org.aicancode.rateguard.config;

import java.nio.file.Path;
import java.time.Clock;
import org.aicancode.rateguard.ai.AdvisorService;
import org.aicancode.rateguard.ai.HttpLlmClient;
import org.aicancode.rateguard.ai.LlmAdvisor;
import org.aicancode.rateguard.ai.RuleAdvisor;
import org.aicancode.rateguard.security.AdminAuth;
import org.aicancode.rateguard.security.ApiKeys;
import org.aicancode.rateguard.service.LimiterService;
import org.aicancode.rateguard.service.TenantService;
import org.aicancode.rateguard.store.RedisStore;
import org.aicancode.rateguard.store.Store;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires real adapters. Unit tests build the same services by hand on the in-memory store. */
@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    public RedisStore store(AppProperties props) {
        return RedisStore.fromUrl(props.redisUrl(), props.redisTimeoutMs(), Path.of(props.luaDir()));
    }

    @Bean
    public AdminAuth adminAuth(AppProperties props) {
        return new AdminAuth(ApiKeys.resolveSecret(props.adminToken(), "ADMIN_TOKEN"));
    }

    @Bean
    public TenantService tenantService(Store store, Clock clock) {
        return new TenantService(store, clock, ApiKeys::newApiKey);
    }

    @Bean
    public LimiterService limiterService(Store store) {
        return new LimiterService(store);
    }

    @Bean
    public AdvisorService advisorService(AppProperties props) {
        LlmAdvisor llm = props.llmEnabled() ? new LlmAdvisor(new HttpLlmClient(props.llmBaseUrl(), props.llmModel(),
                props.llmApiKey(), props.llmTimeoutMs())) : null;
        return new AdvisorService(new RuleAdvisor(), llm);
    }
}
