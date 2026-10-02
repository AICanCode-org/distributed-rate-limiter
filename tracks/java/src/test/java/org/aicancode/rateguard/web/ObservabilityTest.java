package org.aicancode.rateguard.web;

import static org.aicancode.rateguard.support.TestApp.key;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.aicancode.rateguard.ai.AdvisorService;
import org.aicancode.rateguard.ai.BreakerLlmClient;
import org.aicancode.rateguard.ai.LlmAdvisor;
import org.aicancode.rateguard.ai.RuleAdvisor;
import org.aicancode.rateguard.metrics.Metrics;
import org.aicancode.rateguard.resilience.CircuitBreaker;
import org.aicancode.rateguard.support.TestApp;
import org.aicancode.rateguard.support.TestApp.Res;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** v1.1 (Phase 7): request IDs, /metrics and the LLM circuit breaker. */
class ObservabilityTest {

    private final TestApp app = new TestApp();

    private long metric(String name) {
        String text = app.get("/metrics").body();
        return text.lines().filter(l -> l.startsWith(name + " ")).mapToLong(l -> Long.parseLong(l.split(" ")[1]))
                .findFirst().orElseThrow();
    }

    @Test
    void aSafeRequestIdIsEchoedAndAMissingOneIsMinted() {
        assertThat(app.call("GET", "/health", null, Map.of("X-Request-ID", "abc-123")).header("X-Request-ID"))
                .isEqualTo("abc-123");
        assertThat(app.get("/health").header("X-Request-ID")).matches("[0-9a-f-]{36}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "evil\u00e9", "a;b"})
    void unsafeRequestIdsAreReplaced(String value) {
        assertThat(RequestIdFilter.incomingOrNewId(value)).isNotEqualTo(value);
        assertThat(RequestIdFilter.incomingOrNewId("x".repeat(65))).hasSize(36);
    }

    @Test
    void errorsCarryTheRequestIdToo() {
        Res r = app.call("GET", "/api/v1/nope", null, Map.of("X-Request-ID", "trace-404"));
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.header("X-Request-ID")).isEqualTo("trace-404");
    }

    @Test
    void metricsListsEveryCounter() {
        Res r = app.get("/metrics");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.raw().getContentType()).startsWith("text/plain");
        for (String name : Metrics.HELP.keySet()) {
            assertThat(r.body()).contains("# TYPE " + name + " counter").contains("\n" + name + " 0");
        }
    }

    @Test
    void businessCounters() {
        String k = key(app.tenant("acme", 2));
        app.tenant("beta", 5);
        for (int i = 0; i < 3; i++) {
            app.hello(k);
        }
        app.check(k); // another resource: its own bucket
        assertThat(metric(Metrics.TENANTS_CREATED)).isEqualTo(2);
        assertThat(metric(Metrics.DECISIONS_ALLOWED)).isEqualTo(3);
        assertThat(metric(Metrics.DECISIONS_DENIED)).isEqualTo(1);
        assertThat(metric(Metrics.DEGRADED_DECISIONS)).isZero();
    }

    @Test
    void metricsRenderFormat() {
        Metrics m = new Metrics();
        m.inc(Metrics.STORE_ERRORS, 3);
        assertThat(m.value(Metrics.STORE_ERRORS)).isEqualTo(3);
        assertThat(m.render()).contains("rateguard_store_errors_total 3\n").endsWith("\n");
    }

    @Test
    void aDeadLlmIsShortCircuitedAndEveryAnswerFallsBack() {
        Metrics metrics = new Metrics();
        int[] calls = {0};
        CircuitBreaker breaker = new CircuitBreaker("llm", 3, 30_000, () -> 0L, e -> true,
                () -> metrics.inc(Metrics.LLM_SHORT_CIRCUITS));
        LlmAdvisor llm = new LlmAdvisor(new BreakerLlmClient((s, u, n) -> {
            calls[0]++;
            throw new java.net.http.HttpTimeoutException("timed out");
        }, breaker));
        TestApp withLlm = new TestApp(new AdvisorService(new RuleAdvisor(), llm, () -> metrics.inc(Metrics.AI_FALLBACK)));
        withLlm.hello(key(withLlm.tenant()));
        for (int i = 0; i < 4; i++) {
            Res r = withLlm.admin("POST", "/api/v1/admin/tenants/acme/advice");
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.json().get("fallback").asBoolean()).isTrue();
        }
        assertThat(calls[0]).isEqualTo(3);
        assertThat(metrics.value(Metrics.LLM_SHORT_CIRCUITS)).isEqualTo(1);
        assertThat(metrics.value(Metrics.AI_FALLBACK)).isEqualTo(4);
    }
}
