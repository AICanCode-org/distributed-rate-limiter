package org.aicancode.rateguard.ai;

import org.aicancode.rateguard.resilience.CircuitBreaker;

/** v1.1: the LLM behind a circuit breaker, so a dead LLM costs one fast failure, not a timeout per call. */
public class BreakerLlmClient implements LlmClient {

    private final LlmClient client;
    private final CircuitBreaker breaker;

    public BreakerLlmClient(LlmClient client, CircuitBreaker breaker) {
        this.client = client;
        this.breaker = breaker;
    }

    @Override
    public String chat(String system, String user, int maxTokens) throws Exception {
        return breaker.call(() -> client.chat(system, user, maxTokens));
    }
}
