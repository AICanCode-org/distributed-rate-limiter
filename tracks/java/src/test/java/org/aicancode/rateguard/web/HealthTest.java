package org.aicancode.rateguard.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.aicancode.rateguard.support.TestApp;
import org.junit.jupiter.api.Test;

/** Works from day one: the starter already serves /health (no business logic needed). */
class HealthTest {

    @Test
    void reportsRedis() {
        TestApp app = new TestApp();
        assertThat(app.get("/health").json().toString()).isEqualTo("{\"status\":\"UP\",\"checks\":{\"redis\":\"UP\"}}");
        app.memory.up = false;
        TestApp.Res r = app.get("/health");
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.json().toString()).isEqualTo("{\"status\":\"DOWN\",\"checks\":{\"redis\":\"DOWN\"}}");
    }

    @Test
    void answersUnknownRoutesWithProblemJson() {
        TestApp.Res r = new TestApp().get("/api/v1/nope");
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.header("Content-Type")).startsWith("application/problem+json");
        assertThat(r.json().toString()).isEqualTo("{\"type\":\"https://rateguard.example/problems/not-found\","
                + "\"title\":\"Not found\",\"status\":404,\"detail\":\"route not found\"}");
    }
}
