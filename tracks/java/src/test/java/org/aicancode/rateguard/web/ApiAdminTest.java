package org.aicancode.rateguard.web;

import static org.aicancode.rateguard.support.TestApp.KEY1;
import static org.aicancode.rateguard.support.TestApp.key;
import static org.aicancode.rateguard.support.TestApp.policy;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.aicancode.rateguard.support.TestApp;
import org.aicancode.rateguard.support.TestApp.Res;
import org.junit.jupiter.api.Test;

/** M4: the admin API over HTTP (tenants, policies, keys, usage). */
class ApiAdminTest {

    private final TestApp app = new TestApp();

    @Test
    void createTenantReturns201LocationAndTheKeyOnce() {
        Res r = app.admin("POST", "/api/v1/admin/tenants",
                Map.of("id", "acme", "name", " Acme Corp ", "policy", policy("TOKEN_BUCKET", 5, 60)));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.header("Location")).isEqualTo("/api/v1/admin/tenants/acme");
        assertThat(r.body()).isEqualTo("{\"id\":\"acme\",\"name\":\"Acme Corp\",\"policy\":{\"algorithm\":\"TOKEN_BUCKET\","
                + "\"limit\":5,\"windowSeconds\":60,\"failMode\":\"CLOSED\"},\"keyPrefix\":\"rg_000000\","
                + "\"createdAt\":\"2026-10-02T09:30:00.000Z\","
                + "\"updatedAt\":\"2026-10-02T09:30:00.000Z\",\"apiKey\":\"" + KEY1 + "\"}");
        Res got = app.admin("GET", "/api/v1/admin/tenants/acme");
        assertThat(got.status()).isEqualTo(200);
        assertThat(got.json().has("apiKey")).isFalse();
        assertThat(got.body()).doesNotContain(KEY1);
    }

    @Test
    void theAdminApiNeedsTheAdminToken() {
        for (Map<String, String> headers : List.of(Map.<String, String>of(), Map.of("Authorization", "Bearer wrong"),
                Map.of("Authorization", "test-admin-token-at-least-32-characters"))) {
            Res r = app.call("GET", "/api/v1/admin/tenants", null, headers);
            assertThat(r.status()).isEqualTo(401);
            assertThat(r.header("WWW-Authenticate")).isEqualTo("Bearer");
            assertThat(r.header("Content-Type")).startsWith("application/problem+json");
            assertThat(r.json().get("type").asString()).endsWith("/unauthorized");
        }
    }

    @Test
    void aTenantApiKeyIsNotAnAdminToken() {
        String k = key(app.tenant());
        assertThat(app.call("GET", "/api/v1/admin/tenants", null, Map.of("Authorization", "Bearer " + k)).status())
                .isEqualTo(401);
    }

    @Test
    void listIsSortedById() {
        app.tenant("zeta", 5);
        app.tenant("beta", 5);
        Res r = app.admin("GET", "/api/v1/admin/tenants");
        assertThat(r.json().get("items").valueStream().map(t -> t.get("id").asString()).toList())
                .containsExactly("beta", "zeta");
    }

    @Test
    void validationAndConflictsAreProblems() {
        Res r = app.admin("POST", "/api/v1/admin/tenants", Map.of("id", "acme", "name", "A", "policy", Map.of()));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().get("detail").asString()).isEqualTo("policy.algorithm must be TOKEN_BUCKET or SLIDING_WINDOW");
        r = app.admin("POST", "/api/v1/admin/tenants", "{nope");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().get("detail").asString()).isEqualTo("request body must be a JSON object");
        app.tenant();
        r = app.admin("POST", "/api/v1/admin/tenants",
                Map.of("id", "acme", "name", "Again", "policy", policy("TOKEN_BUCKET", 1, 1)));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.json().get("type").asString()).endsWith("/tenant-exists");
    }

    @Test
    void unknownTenantIs404() {
        for (List<String> c : List.of(List.of("GET", ""), List.of("DELETE", ""), List.of("POST", "/reset"),
                List.of("POST", "/rotate-key"), List.of("GET", "/usage"))) {
            Res r = app.admin(c.get(0), "/api/v1/admin/tenants/ghost" + c.get(1));
            assertThat(r.status()).as(c.toString()).isEqualTo(404);
            assertThat(r.json().get("detail").asString()).isEqualTo("tenant not found");
        }
    }

    @Test
    void putPolicy() {
        app.tenant();
        app.clock.advance(Duration.ofMinutes(1));
        Res r = app.admin("PUT", "/api/v1/admin/tenants/acme/policy", policy("SLIDING_WINDOW", 100, 3600));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("policy").toString())
                .isEqualTo("{\"algorithm\":\"SLIDING_WINDOW\",\"limit\":100,\"windowSeconds\":3600,\"failMode\":\"CLOSED\"}");
        assertThat(r.json().get("updatedAt").asString()).isEqualTo("2026-10-02T09:31:00.000Z");
        assertThat(app.admin("PUT", "/api/v1/admin/tenants/acme/policy", Map.of("algorithm", "TOKEN_BUCKET")).status())
                .isEqualTo(400);
    }

    @Test
    void rotateKey() {
        String old = key(app.tenant());
        Res r = app.admin("POST", "/api/v1/admin/tenants/acme/rotate-key");
        assertThat(r.status()).isEqualTo(200);
        assertThat(key(r.json())).isNotEqualTo(old);
        assertThat(r.json().get("keyPrefix").asString()).isEqualTo("rg_000000");
        assertThat(app.check(old).status()).isEqualTo(401);
        assertThat(app.check(key(r.json())).status()).isEqualTo(200);
    }

    @Test
    void resetGivesAFullBucketBack() {
        String k = key(app.tenant("acme", 1));
        assertThat(app.check(k).json().get("allowed").asBoolean()).isTrue();
        assertThat(app.check(k).json().get("allowed").asBoolean()).isFalse();
        assertThat(app.admin("POST", "/api/v1/admin/tenants/acme/reset").status()).isEqualTo(204);
        assertThat(app.check(k).json().get("allowed").asBoolean()).isTrue();
    }

    @Test
    void deleteTenantRevokesItsKey() {
        String k = key(app.tenant());
        assertThat(app.admin("DELETE", "/api/v1/admin/tenants/acme").status()).isEqualTo(204);
        assertThat(app.admin("GET", "/api/v1/admin/tenants/acme").status()).isEqualTo(404);
        assertThat(app.check(k).status()).isEqualTo(401);
    }

    @Test
    void usageSeries() {
        String k = key(app.tenant("acme", "TOKEN_BUCKET", 2, 3600));
        for (int i = 0; i < 3; i++) {
            app.check(k);
        }
        app.clock.advance(Duration.ofMinutes(1));
        app.check(k);
        Res r = app.admin("GET", "/api/v1/admin/tenants/acme/usage?minutes=3");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"tenantId\":\"acme\",\"minutes\":3,\"totals\":{\"allowed\":2,\"denied\":2},"
                + "\"series\":[{\"minute\":\"2026-10-02T09:29:00.000Z\",\"allowed\":0,\"denied\":0},"
                + "{\"minute\":\"2026-10-02T09:30:00.000Z\",\"allowed\":2,\"denied\":1},"
                + "{\"minute\":\"2026-10-02T09:31:00.000Z\",\"allowed\":0,\"denied\":1}]}");
        assertThat(app.admin("GET", "/api/v1/admin/tenants/acme/usage?minutes=0").status()).isEqualTo(400);
    }

    @Test
    void storeOutageOnTheAdminApiIs503() {
        app.tenant();
        app.memory.up = false;
        Res r = app.admin("GET", "/api/v1/admin/tenants");
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.header("Retry-After")).isEqualTo("1");
        assertThat(r.json().get("type").asString()).endsWith("/limiter-unavailable");
    }
}
