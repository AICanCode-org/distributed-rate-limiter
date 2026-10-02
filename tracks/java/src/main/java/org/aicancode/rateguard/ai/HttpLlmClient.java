package org.aicancode.rateguard.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import tools.jackson.databind.json.JsonMapper;

/** POST {baseUrl}/chat/completions on any OpenAI-compatible server: Ollama locally, or a free Gemini/Groq key. */
public class HttpLlmClient implements LlmClient {

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public HttpLlmClient(String baseUrl, String model, String apiKey, int timeoutMs) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String chat(String system, String user, int maxTokens) throws Exception {
        // TODO(phase-3 M5): POST {base}/chat/completions (OpenAI-compatible) with model, temperature 0,
        //   max_tokens and the two messages, a timeout of LLM_TIMEOUT_MS, Bearer LLM_API_KEY if set; return
        //   choices[0].message.content; anything wrong throws.
        throw new UnsupportedOperationException("TODO(phase-3 M5): HttpLlmClient.chat");
    }
}
