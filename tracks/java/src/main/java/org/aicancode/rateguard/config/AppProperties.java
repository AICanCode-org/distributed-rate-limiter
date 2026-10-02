package org.aicancode.rateguard.config;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Configuration from environment variables (12-factor). Same names in every track. */
@Component
public class AppProperties {

    private final String redisUrl;
    private final int redisTimeoutMs;
    private final int redisBreakerCooldownMs;
    private final String luaDir;
    private final String adminToken;
    private final String aiProvider;
    private final String llmBaseUrl;
    private final String llmApiKey;
    private final String llmModel;
    private final int llmTimeoutMs;

    public AppProperties(
            @Value("${REDIS_URL:redis://localhost:6379/0}") String redisUrl,
            @Value("${REDIS_TIMEOUT_MS:1000}") int redisTimeoutMs,
            @Value("${REDIS_BREAKER_COOLDOWN_MS:5000}") int redisBreakerCooldownMs,
            @Value("${LUA_DIR:../../spec/lua}") String luaDir,
            @Value("${ADMIN_TOKEN:}") String adminToken,
            @Value("${AI_PROVIDER:offline}") String aiProvider,
            @Value("${LLM_BASE_URL:http://localhost:11434/v1}") String llmBaseUrl,
            @Value("${LLM_API_KEY:}") String llmApiKey,
            @Value("${LLM_MODEL:llama3.2:1b}") String llmModel,
            @Value("${LLM_TIMEOUT_MS:5000}") int llmTimeoutMs) {
        this.redisUrl = redisUrl;
        this.redisTimeoutMs = redisTimeoutMs;
        this.redisBreakerCooldownMs = redisBreakerCooldownMs;
        this.luaDir = luaDir;
        this.adminToken = adminToken;
        this.aiProvider = aiProvider.toLowerCase(Locale.ROOT);
        this.llmBaseUrl = llmBaseUrl.replaceAll("/+$", "");
        this.llmApiKey = llmApiKey;
        this.llmModel = llmModel;
        this.llmTimeoutMs = llmTimeoutMs;
    }

    public String redisUrl() {
        return redisUrl;
    }

    public int redisTimeoutMs() {
        return redisTimeoutMs;
    }

    /** v1.1: how long the Redis circuit stays open before one trial call. */
    public int redisBreakerCooldownMs() {
        return redisBreakerCooldownMs;
    }

    /** Where token_bucket.lua and sliding_window.lua live (spec/lua; /app/lua in the Docker image). */
    public String luaDir() {
        return luaDir;
    }

    /** Empty means "not set": a random per-process token is used, so the admin API is locked (dev only). */
    public String adminToken() {
        return adminToken;
    }

    public boolean llmEnabled() {
        return "llm".equals(aiProvider);
    }

    public String llmBaseUrl() {
        return llmBaseUrl;
    }

    public String llmApiKey() {
        return llmApiKey;
    }

    public String llmModel() {
        return llmModel;
    }

    public int llmTimeoutMs() {
        return llmTimeoutMs;
    }
}
