package org.aicancode.rateguard.web;

import static org.aicancode.rateguard.support.TestApp.key;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.aicancode.rateguard.support.TestApp;
import org.aicancode.rateguard.support.TestApp.Res;
import org.junit.jupiter.api.Test;

/** M4: the decision API and the rate-limited demo route over HTTP. */
class ApiLimitsTest {

    private final TestApp app = new TestApp();

    @Test
    void checkReturnsTheDecisionAndHeaders() {
        String k = key(app.tenant("acme", 5));
        Res r = app.check(k, Map.of("resource", "orders.create", "cost", 2));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"allowed\":true,\"tenantId\":\"acme\",\"resource\":\"orders.create\",\"cost\":2,"
                + "\"algorithm\":\"TOKEN_BUCKET\",\"limit\":5,\"remaining\":3,\"resetAfterMs\":24000,\"retryAfterMs\":0,"
                + "\"degraded\":false}");
        assertThat(r.header("X-RateLimit-Limit")).isEqualTo("5");
        assertThat(r.header("X-RateLimit-Remaining")).isEqualTo("3");
        assertThat(r.header("X-RateLimit-Reset")).isEqualTo("24");
    }

    @Test
    void aDeniedCheckIsStillA200Answer() {
        String k = key(app.tenant("acme", 1));
        app.check(k);
        Res r = app.check(k);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("allowed").asBoolean()).isFalse();
        assertThat(r.json().get("retryAfterMs").asLong()).isEqualTo(60_000);
        assertThat(r.header("Retry-After")).isNull();
    }

    @Test
    void checkWithoutABodyUsesTheDefaults() {
        Res r = app.check(key(app.tenant()));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("resource").asString()).isEqualTo("default");
        assertThat(r.json().get("cost").asInt()).isEqualTo(1);
    }

    @Test
    void checkNeedsAValidApiKey() {
        app.tenant();
        for (Map<String, String> headers : List.of(Map.<String, String>of(), Map.of("X-API-Key", "nope"),
                Map.of("X-API-Key", "rg_" + "0".repeat(31) + "9"))) {
            Res r = app.call("POST", "/api/v1/limits/check", null, headers);
            assertThat(r.status()).isEqualTo(401);
            assertThat(r.json().get("type").asString()).endsWith("/invalid-api-key");
            assertThat(r.header("WWW-Authenticate")).isNull();
        }
    }

    @Test
    void checkErrors() {
        String k = key(app.tenant("acme", 3));
        assertThat(app.check(k, Map.of("cost", 0)).status()).isEqualTo(400);
        Res r = app.check(k, Map.of("cost", 4));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.json().get("detail").asString()).isEqualTo("cost 4 is more than the limit of 3");
    }

    @Test
    void slidingWindowTenant() {
        String k = key(app.tenant("acme", "SLIDING_WINDOW", 2, 10));
        List<Boolean> out = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            out.add(app.check(k).json().get("allowed").asBoolean());
        }
        assertThat(out).containsExactly(true, true, false);
        app.clock.advance(Duration.ofSeconds(20));
        assertThat(app.check(k).json().get("allowed").asBoolean()).isTrue();
    }

    @Test
    void helloIsRateLimitedWith429AndRetryAfter() {
        String k = key(app.tenant("acme", 2));
        Res ok = app.hello(k);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo("{\"message\":\"Hello, Acme Corp!\",\"tenantId\":\"acme\"}");
        assertThat(ok.header("X-RateLimit-Remaining")).isEqualTo("1");
        app.hello(k);
        Res r = app.hello(k);
        assertThat(r.status()).isEqualTo(429);
        assertThat(r.header("Content-Type")).startsWith("application/problem+json");
        assertThat(r.header("Retry-After")).isEqualTo("30");
        assertThat(r.header("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(r.body()).isEqualTo("{\"type\":\"https://rateguard.example/problems/rate-limited\","
                + "\"title\":\"Too many requests\",\"status\":429,\"detail\":\"limit of 2 per 60 s reached, retry in 30 s\"}");
    }

    @Test
    void helloAndCheckUseSeparateBuckets() {
        String k = key(app.tenant("acme", 1));
        assertThat(app.hello(k).status()).isEqualTo(200);
        assertThat(app.check(k).json().get("allowed").asBoolean()).isTrue(); // resource "default", not "demo"
    }

    @Test
    void tenantsDoNotShareLimits() {
        String a = key(app.tenant("acme", 1));
        String b = key(app.tenant("beta", 1));
        assertThat(app.hello(a).status()).isEqualTo(200);
        assertThat(app.hello(a).status()).isEqualTo(429);
        assertThat(app.hello(b).status()).isEqualTo(200);
    }

    @Test
    void storeOutageFailsClosed() {
        String k = key(app.tenant());
        app.memory.up = false;
        Res r = app.hello(k);
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.json().get("type").asString()).endsWith("/limiter-unavailable");
    }
}
