package org.aicancode.rateguard.ai;

/** One chat completion. Implementations throw on any problem (timeout, HTTP error, empty answer). */
@FunctionalInterface
public interface LlmClient {

    String chat(String system, String user, int maxTokens) throws Exception;
}
