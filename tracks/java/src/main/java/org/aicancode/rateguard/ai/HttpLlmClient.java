package org.aicancode.rateguard.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("max_tokens", maxTokens);
        body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("LLM HTTP " + response.statusCode());
        }
        Map<?, ?> parsed = json.readValue(response.body(), Map.class);
        List<?> choices = (List<?>) parsed.get("choices");
        Map<?, ?> message = choices == null || choices.isEmpty() ? null
                : (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
        Object text = message == null ? null : message.get("content");
        if (!(text instanceof String s) || s.isBlank()) {
            throw new IllegalStateException("empty LLM response");
        }
        return s.strip();
    }
}
