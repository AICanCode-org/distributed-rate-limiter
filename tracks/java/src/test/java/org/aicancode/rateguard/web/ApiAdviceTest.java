package org.aicancode.rateguard.web;

import static org.aicancode.rateguard.support.TestApp.key;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.aicancode.rateguard.ai.AdvisorService;
import org.aicancode.rateguard.ai.LlmAdvisor;
import org.aicancode.rateguard.ai.RuleAdvisor;
import org.aicancode.rateguard.support.TestApp;
import org.aicancode.rateguard.support.TestApp.Res;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M5: POST /admin/tenants/{id}/advice over HTTP. Advice is returned, never applied. */
class ApiAdviceTest {

    @Test
    void adviceOfflineForABusyTenant() {
        TestApp app = new TestApp();
        String k = key(app.tenant("acme", "TOKEN_BUCKET", 2, 3600));
        for (int i = 0; i < 5; i++) {
            app.check(k);
        }
        Res r = app.admin("POST", "/api/v1/admin/tenants/acme/advice?minutes=10");
        assertThat(r.status()).isEqualTo(200);
        JsonNode body = r.json();
        assertThat(List.of(body.get("tenantId").asString(), body.get("provider").asString(), body.get("fallback").asBoolean()))
                .isEqualTo(List.of("acme", "rules", false));
        assertThat(body.get("currentPolicy").toString())
                .isEqualTo("{\"algorithm\":\"TOKEN_BUCKET\",\"limit\":2,\"windowSeconds\":3600,\"failMode\":\"CLOSED\"}");
        assertThat(List.of(body.at("/stats/allowed").asInt(), body.at("/stats/denied").asInt(),
                body.at("/stats/minutes").asInt())).isEqualTo(List.of(2, 3, 10));
        assertThat(body.get("action").asString()).isEqualTo("RAISE_LIMIT");
        assertThat(body.get("suggestedPolicy").toString())
                .isEqualTo("{\"algorithm\":\"TOKEN_BUCKET\",\"limit\":20,\"windowSeconds\":3600,\"failMode\":\"CLOSED\"}");
        assertThat(body.get("summary").asString()).startsWith("60% of requests were denied (3 of 5).");
        assertThat(body.get("generatedAt").asString()).isEqualTo("2026-10-02T09:30:00.000Z");
        // nothing was applied
        assertThat(app.admin("GET", "/api/v1/admin/tenants/acme").json().at("/policy/limit").asInt()).isEqualTo(2);
    }

    @Test
    void adviceKeepHasNoSuggestedPolicy() {
        TestApp app = new TestApp();
        app.tenant();
        JsonNode body = app.admin("POST", "/api/v1/admin/tenants/acme/advice").json();
        assertThat(body.get("action").asString()).isEqualTo("KEEP");
        assertThat(body.get("suggestedPolicy").isNull()).isTrue();
        assertThat(body.at("/stats/minutes").asInt()).isEqualTo(60);
    }

    @Test
    void adviceFallsBackWhenTheLlmIsDown() {
        TestApp app = new TestApp(new AdvisorService(new RuleAdvisor(), new LlmAdvisor((system, user, max) -> {
            throw new TimeoutException("LLM timed out");
        })));
        app.tenant();
        Res r = app.admin("POST", "/api/v1/admin/tenants/acme/advice");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("provider").asString()).isEqualTo("rules");
        assertThat(r.json().get("fallback").asBoolean()).isTrue();
    }

    @Test
    void adviceErrors() {
        TestApp app = new TestApp();
        assertThat(app.call("POST", "/api/v1/admin/tenants/acme/advice", null, Map.of()).status()).isEqualTo(401);
        assertThat(app.admin("POST", "/api/v1/admin/tenants/ghost/advice").status()).isEqualTo(404);
        app.tenant();
        assertThat(app.admin("POST", "/api/v1/admin/tenants/acme/advice?minutes=121").status()).isEqualTo(400);
    }
}
