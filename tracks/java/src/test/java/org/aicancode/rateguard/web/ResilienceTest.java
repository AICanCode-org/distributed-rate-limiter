package org.aicancode.rateguard.web;

import static org.aicancode.rateguard.support.TestApp.key;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.aicancode.rateguard.metrics.Metrics;
import org.aicancode.rateguard.support.TestApp;
import org.aicancode.rateguard.support.TestApp.Res;
import org.junit.jupiter.api.Test;

/** v1.1 (Phase 7): what RateGuard does while Redis is unavailable (ADR-0006: failMode). */
class ResilienceTest {

    private final TestApp app = new TestApp();

    private String knownTenant(int limit, String failMode) {
        String k = key(app.tenant("acme", "TOKEN_BUCKET", limit, 60, failMode));
        app.hello(k); // Redis has seen the tenant once: its policy is now known locally
        return k;
    }

    @Test
    void closedIsTheDefaultAndAnswers503() {
        String k = knownTenant(5, null);
        app.memory.up = false;
        Res r = app.hello(k);
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.json().get("type").asString()).endsWith("/limiter-unavailable");
        assertThat(r.header("Retry-After")).isEqualTo("1");
        assertThat(app.metrics.value(Metrics.DEGRADED_DECISIONS)).isZero();
    }

    @Test
    void openAllowsAndMarksTheAnswerDegraded() {
        String k = knownTenant(5, "OPEN");
        app.memory.up = false;
        for (int i = 0; i < 10; i++) {
            Res r = app.check(k, Map.of("cost", 2));
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.json().get("allowed").asBoolean()).isTrue();
            assertThat(r.json().get("degraded").asBoolean()).isTrue();
            assertThat(r.json().get("remaining").asInt()).isEqualTo(3);
            assertThat(r.header("X-RateLimit-Degraded")).isEqualTo("true");
        }
        assertThat(app.metrics.value(Metrics.DEGRADED_DECISIONS)).isEqualTo(10);
    }

    @Test
    void localKeepsLimitingOnThisInstance() {
        String k = knownTenant(2, "LOCAL");
        app.memory.up = false;
        List<Integer> codes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            codes.add(app.hello(k).status());
        }
        assertThat(codes).containsExactly(200, 200, 429);
        Res denied = app.hello(k);
        assertThat(denied.header("X-RateLimit-Degraded")).isEqualTo("true");
        assertThat(denied.header("Retry-After")).isEqualTo("30");
        Res first = app.check(k, Map.of("resource", "orders.create", "cost", 2));
        assertThat(first.json().get("allowed").asBoolean()).isTrue();
        assertThat(first.json().get("remaining").asInt()).isZero();
        assertThat(first.json().get("degraded").asBoolean()).isTrue();
        assertThat(app.check(k, Map.of("resource", "orders.create")).json().get("allowed").asBoolean()).isFalse();
        assertThat(app.metrics.value(Metrics.DECISIONS_DENIED)).isEqualTo(3);
    }

    @Test
    void anUnknownKeyDuringAnOutageIs503NotAllowed() {
        String k = key(app.tenant("acme", "TOKEN_BUCKET", 5, 60, "OPEN")); // never used: not cached
        app.memory.up = false;
        assertThat(app.hello(k).status()).isEqualTo(503);
        assertThat(app.hello("rg_" + "f".repeat(32)).status()).isEqualTo(503);
    }

    @Test
    void theBreakerStopsCallingRedisAndRecovers() {
        String k = knownTenant(5, "OPEN");
        app.memory.up = false;
        for (int i = 0; i < 6; i++) {
            assertThat(app.hello(k).status()).isEqualTo(200);
        }
        // 3 real failures open the circuit; the other calls are skipped (no timeout to wait for)
        assertThat(app.metrics.value(Metrics.STORE_ERRORS)).isEqualTo(3);
        assertThat(app.metrics.value(Metrics.STORE_SHORT_CIRCUITS)).isGreaterThanOrEqualTo(3);
        assertThat(app.get("/health").json().get("status").asString()).isEqualTo("DEGRADED");
        app.memory.up = true;
        assertThat(app.get("/health").json().get("status").asString()).isEqualTo("DEGRADED"); // no trial yet
        app.breakerNow.addAndGet(5000); // REDIS_BREAKER_COOLDOWN_MS
        assertThat(app.get("/health").json().toString()).isEqualTo("{\"status\":\"UP\",\"checks\":{\"redis\":\"UP\"}}");
        Res r = app.check(k);
        assertThat(r.json().get("degraded").asBoolean()).isFalse();
        assertThat(r.header("X-RateLimit-Degraded")).isNull();
    }

    @Test
    void aDuplicateIdDoesNotOpenTheBreaker() {
        app.tenant();
        Map<String, Object> body = Map.of("id", "acme", "name", "Again", "policy", TestApp.policy("TOKEN_BUCKET", 1, 1));
        for (int i = 0; i < 4; i++) {
            assertThat(app.admin("POST", "/api/v1/admin/tenants", body).status()).isEqualTo(409);
        }
        assertThat(app.get("/health").json().get("status").asString()).isEqualTo("UP");
        assertThat(app.metrics.value(Metrics.STORE_ERRORS)).isZero();
    }

    @Test
    void failModeRoundTripsAndIsValidated() {
        assertThat(app.tenant("acme", "TOKEN_BUCKET", 5, 60, "LOCAL").at("/policy/failMode").asString()).isEqualTo("LOCAL");
        Map<String, Object> policy = TestApp.policy("TOKEN_BUCKET", 5, 60);
        policy.put("failMode", "OPEN");
        Res r = app.admin("PUT", "/api/v1/admin/tenants/acme/policy", policy);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().at("/policy/failMode").asString()).isEqualTo("OPEN");
        policy.put("failMode", "MAYBE");
        Res bad = app.admin("PUT", "/api/v1/admin/tenants/acme/policy", policy);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.json().get("detail").asString()).isEqualTo("policy.failMode must be CLOSED, OPEN or LOCAL");
    }

    @Test
    void theAdviceKeepsTheFailMode() {
        String k = key(app.tenant("acme", "TOKEN_BUCKET", 2, 3600, "LOCAL"));
        for (int i = 0; i < 6; i++) {
            app.hello(k);
        }
        Res r = app.admin("POST", "/api/v1/admin/tenants/acme/advice");
        assertThat(r.json().at("/suggestedPolicy/failMode").asString()).isEqualTo("LOCAL");
    }
}
