package org.aicancode.rateguard.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.aicancode.rateguard.ai.Advice.Action;
import org.aicancode.rateguard.domain.Models.Algorithm;
import org.aicancode.rateguard.domain.Models.Policy;
import org.aicancode.rateguard.domain.Models.Tenant;
import org.aicancode.rateguard.domain.Models.UsagePoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** M5: the AI policy advisor: statistics by code, advice by rules (offline) or a validated LLM answer. */
class AdvisorTest {

    private static final long M0 = 1_790_933_400_000L / 60_000; // 2026-10-02T09:30Z as an epoch minute
    private static final Policy TB10 = new Policy(Algorithm.TOKEN_BUCKET, 10, 60);
    private static final Instant NOW = Instant.parse("2026-10-02T09:30:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** pts(a0, d0, a1, d1, ...): one usage point per (allowed, denied) pair. */
    private static List<UsagePoint> pts(long... counts) {
        List<UsagePoint> out = new ArrayList<>();
        for (int i = 0; i < counts.length; i += 2) {
            out.add(new UsagePoint(M0 + i / 2, counts[i], counts[i + 1]));
        }
        return out;
    }

    private static long[] times(int n, long allowed, long denied) {
        long[] out = new long[2 * n];
        for (int i = 0; i < n; i++) {
            out[2 * i] = allowed;
            out[2 * i + 1] = denied;
        }
        return out;
    }

    private static long[] concat(long[]... parts) {
        return Arrays.stream(parts).flatMapToLong(Arrays::stream).toArray();
    }

    private static Tenant tenant() {
        return new Tenant("acme", "Acme Corp", TB10, "h", "rg_000000", NOW, NOW);
    }

    private static Advice advise(Policy policy, List<UsagePoint> points) {
        return new RuleAdvisor().advise(policy, UsageStats.summarize(policy, points));
    }

    private static Policy policy(Algorithm algorithm, int limit, int window) {
        return new Policy(algorithm, limit, window);
    }

    @Test
    void summarizeComputesIntegerStatistics() {
        UsageStats s = UsageStats.summarize(TB10, pts(0, 0, 3, 0, 1, 1, 6, 4, 0, 0));
        assertThat(List.of(s.minutes(), s.allowed(), s.denied(), s.total(), s.deniedPercent()))
                .isEqualTo(List.of(5, 10L, 5L, 15L, 33L));
        assertThat(List.of(s.peak(), s.peakMinute(), s.peakDenied())).isEqualTo(List.of(10L, M0 + 3, 4L));
        assertThat(List.of(s.median(), s.p90(), (long) s.activeMinutes(), s.capacityPerMinute()))
                .isEqualTo(List.of(3L, 10L, 3L, 10L));
    }

    @Test
    void summarizeWithoutTraffic() {
        UsageStats s = UsageStats.summarize(policy(Algorithm.SLIDING_WINDOW, 1, 3600), pts(0, 0, 0, 0));
        assertThat(Arrays.asList(s.total(), s.peak(), s.peakMinute(), s.median(), s.p90(), s.deniedPercent()))
                .isEqualTo(Arrays.asList(0L, 0L, null, 0L, 0L, 0L));
        assertThat(s.capacityPerMinute()).isZero(); // 1 per hour rounds down to 0 per minute
    }

    @Test
    void statsJsonIsTheApiShape() {
        assertThat(JSON.writeValueAsString(UsageStats.summarize(TB10, pts(0, 0, 6, 4)).toJson()))
                .isEqualTo("{\"minutes\":2,\"allowed\":6,\"denied\":4,\"deniedPercent\":40,\"peakPerMinute\":10,"
                        + "\"peakMinute\":\"2026-10-02T09:31:00.000Z\",\"peakMinuteDenied\":4,\"medianPerMinute\":10,"
                        + "\"p90PerMinute\":10,\"activeMinutes\":1,\"capacityPerMinute\":10}");
    }

    @Test
    void ruleNoTrafficKeepsThePolicy() {
        assertThat(advise(TB10, pts(times(5, 0, 0)))).isEqualTo(
                new Advice(Action.KEEP, null, "No requests in the last 5 minutes: keep the current policy."));
    }

    @Test
    void ruleASpikeIsAnAnomalyToInvestigate() {
        assertThat(advise(TB10, pts(concat(times(7, 2, 0), new long[] {10, 30}, times(2, 2, 0))))).isEqualTo(
                new Advice(Action.INVESTIGATE, null, "Spike at 09:37 UTC: 40 requests in one minute, 20x the usual 2. "
                        + "30 of the 30 denials happened in that minute, which looks like a retry storm or abuse, not "
                        + "growth. Keep the limit and check the client."));
    }

    @Test
    void ruleSteadyDemandAboveTheLimitRaisesItWithHeadroom() {
        assertThat(advise(TB10, pts(times(10, 10, 2)))).isEqualTo(new Advice(Action.RAISE_LIMIT, 15,
                "16% of requests were denied (20 of 120). Steady demand is about 12 per minute (90th percentile) against "
                        + "a capacity of 10 per minute. Raise the limit to 15 per 60 s (20% headroom)."));
    }

    @Test
    void ruleRaiseScalesToTheWindowAndIsCappedAtTenTimes() {
        assertThat(advise(policy(Algorithm.TOKEN_BUCKET, 100, 3600), pts(times(10, 1, 2))).limit()).isEqualTo(216);
        assertThat(advise(policy(Algorithm.TOKEN_BUCKET, 1, 60), pts(times(10, 1, 49))).limit()).isEqualTo(10);
    }

    @Test
    void ruleRaiseIsSkippedAtTheMaximumLimit() {
        assertThat(advise(policy(Algorithm.TOKEN_BUCKET, 100_000, 60), pts(times(3, 100_000, 30_000))).action())
                .isEqualTo(Action.KEEP);
    }

    @Test
    void ruleALimitFarAboveAnyUseCanBeLowered() {
        assertThat(advise(policy(Algorithm.SLIDING_WINDOW, 1000, 60), pts(times(12, 5, 0)))).isEqualTo(
                new Advice(Action.LOWER_LIMIT, 10, "Peak demand was 5 per minute, under a quarter of the capacity of "
                        + "1000 per minute. 10 per 60 s (twice the peak) still fits and limits the damage if the key leaks."));
    }

    @Test
    void ruleLoweringNeedsEnoughHistory() {
        assertThat(advise(policy(Algorithm.SLIDING_WINDOW, 1000, 60), pts(times(9, 5, 0))).action()).isEqualTo(Action.KEEP);
    }

    @Test
    void ruleOtherwiseKeep() {
        assertThat(advise(TB10, pts(times(12, 6, 0)))).isEqualTo(new Advice(Action.KEEP, null,
                "0% denied and a peak of 6 per minute against a capacity of 10 per minute: the current policy fits."));
    }

    @Test
    void parseAcceptsAValidAnswerAndCodeFences() {
        assertThat(LlmAdvisor.parse("{\"action\":\"RAISE_LIMIT\",\"limit\":20,\"summary\":\"Steady  demand.\"}", TB10))
                .isEqualTo(new Advice(Action.RAISE_LIMIT, 20, "Steady demand."));
        String fenced = "```json\n{\"action\": \"INVESTIGATE\", \"limit\": null, \"summary\": \"Spike.\"}\n```";
        assertThat(LlmAdvisor.parse(fenced, TB10)).isEqualTo(new Advice(Action.INVESTIGATE, null, "Spike."));
        assertThat(LlmAdvisor.parse("{\"action\": \"KEEP\", \"summary\": \"Fine.\"}", TB10).limit()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not json",
        "[1, 2]",
        "{\"action\": \"DELETE_TENANT\", \"summary\": \"x\"}",
        "{\"action\": \"RAISE_LIMIT\", \"limit\": true, \"summary\": \"x\"}",
        "{\"action\": \"RAISE_LIMIT\", \"limit\": 10, \"summary\": \"x\"}",
        "{\"action\": \"RAISE_LIMIT\", \"limit\": 101, \"summary\": \"x\"}",
        "{\"action\": \"LOWER_LIMIT\", \"limit\": 10, \"summary\": \"x\"}",
        "{\"action\": \"LOWER_LIMIT\", \"limit\": 0, \"summary\": \"x\"}",
        "{\"action\": \"KEEP\", \"limit\": 12, \"summary\": \"x\"}",
        "{\"action\": \"KEEP\", \"summary\": \"   \"}",
        "{\"action\": \"KEEP\", \"summary\": 5}",
        "{\"action\": \"RAISE_LIMIT\", \"limit\": 15.5, \"summary\": \"x\"}",
    })
    void parseRejectsAnythingUnexpected(String answer) {
        assertThatThrownBy(() -> LlmAdvisor.parse(answer, TB10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsASummaryAboveFourHundredCharacters() {
        String answer = "{\"action\": \"KEEP\", \"summary\": \"" + "x".repeat(401) + "\"}";
        assertThatThrownBy(() -> LlmAdvisor.parse(answer, TB10)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Records each call and answers (or fails) like a real LLM would. */
    static final class FakeChat implements LlmClient {
        final List<List<Object>> calls = new ArrayList<>();
        private final String answer;
        private final Exception error;

        FakeChat(String answer, Exception error) {
            this.answer = answer;
            this.error = error;
        }

        @Override
        public String chat(String system, String user, int maxTokens) throws Exception {
            calls.add(List.of(system, user, maxTokens));
            if (error != null) {
                throw error;
            }
            return answer;
        }
    }

    @Test
    void llmAdvisorSendsOnlyNumbersInsideStatsTags() throws Exception {
        FakeChat chat = new FakeChat("{\"action\": \"KEEP\", \"limit\": null, \"summary\": \"Looks fine.\"}", null);
        UsageStats s = UsageStats.summarize(TB10, pts(3, 0, 4, 0));
        assertThat(new LlmAdvisor(chat).advise(tenant(), s)).isEqualTo(new Advice(Action.KEEP, null, "Looks fine."));
        assertThat(chat.calls.getFirst().get(0)).isEqualTo(LlmAdvisor.SYSTEM_PROMPT);
        assertThat(chat.calls.getFirst().get(2)).isEqualTo(300);
        String user = (String) chat.calls.getFirst().get(1);
        assertThat(user).startsWith("<stats>").endsWith("</stats>").doesNotContain("Acme").doesNotContain("rg_");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("policy", LlmAdvisor.policyJson(TB10));
        facts.put("stats", s.toJson());
        assertThat(user.substring("<stats>".length(), user.length() - "</stats>".length()))
                .isEqualTo(JSON.writeValueAsString(facts));
    }

    @Test
    void serviceUsesTheLlmWhenItAnswersWell() {
        AdvisorService svc = new AdvisorService(new RuleAdvisor(),
                new LlmAdvisor(new FakeChat("{\"action\": \"INVESTIGATE\", \"summary\": \"Odd spike.\"}", null)));
        Advice.Result r = svc.advise(tenant(), UsageStats.summarize(TB10, pts(1, 0)));
        assertThat(List.of(r.provider(), r.fallback(), r.advice().action())).isEqualTo(List.of("llm", false, Action.INVESTIGATE));
    }

    @Test
    void serviceFallsBackToTheRulesOnAnyLlmFailure() {
        for (FakeChat chat : List.of(new FakeChat(null, new TimeoutException("down")),
                new FakeChat("I think you should relax.", null))) {
            Advice.Result r = new AdvisorService(new RuleAdvisor(), new LlmAdvisor(chat))
                    .advise(tenant(), UsageStats.summarize(TB10, pts(0, 0)));
            assertThat(List.of(r.provider(), r.fallback(), r.advice().action())).isEqualTo(List.of("rules", true, Action.KEEP));
        }
    }

    @Test
    void serviceIsOfflineByDefault() {
        Advice.Result r = new AdvisorService(new RuleAdvisor(), null).advise(tenant(), UsageStats.summarize(TB10, pts(0, 0)));
        assertThat(List.of(r.provider(), r.fallback())).isEqualTo(List.of("rules", false));
    }

    /** A tiny local HTTP server that answers like an OpenAI-compatible /chat/completions endpoint. */
    private static HttpServer server(int status, String body, Map<String, Object> seen) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            seen.put("auth", exchange.getRequestHeaders().getFirst("Authorization"));
            seen.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void llmClientSpeaksOpenAiChatCompletions() throws Exception {
        Map<String, Object> seen = new LinkedHashMap<>();
        HttpServer server = server(200, "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}", seen);
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            assertThat(new HttpLlmClient(base, "tiny", "secret-key", 2000).chat("sys", "user", 50)).isEqualTo("hi");
            assertThat(seen.get("auth")).isEqualTo("Bearer secret-key");
            JsonNode body = JSON.readTree((String) seen.get("body"));
            assertThat(body.get("model").asString()).isEqualTo("tiny");
            assertThat(body.get("temperature").asInt()).isZero();
            assertThat(body.get("max_tokens").asInt()).isEqualTo(50);
            assertThat(body.get("messages").valueStream().map(m -> m.get("role").asString()).toList())
                    .containsExactly("system", "user");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void llmClientThrowsOnHttpErrorsAndEmptyAnswers() throws Exception {
        for (List<Object> c : List.of(List.<Object>of(500, "boom"),
                List.<Object>of(200, "{\"choices\":[{\"message\":{\"content\":\" \"}}]}"))) {
            HttpServer server = server((int) c.get(0), (String) c.get(1), new LinkedHashMap<>());
            try {
                String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
                assertThatThrownBy(() -> new HttpLlmClient(base, "tiny", "", 2000).chat("sys", "user", 10))
                        .isInstanceOf(IllegalStateException.class);
            } finally {
                server.stop(0);
            }
        }
    }
}
