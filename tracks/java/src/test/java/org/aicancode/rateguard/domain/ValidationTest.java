package org.aicancode.rateguard.domain;

import static org.aicancode.rateguard.support.Problems.message;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.aicancode.rateguard.domain.Models.Algorithm;
import org.aicancode.rateguard.domain.Models.Policy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** M1: input validation. The messages are part of the contract (identical in every track). */
class ValidationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode j(String json) {
        return JSON.readTree(json);
    }

    private static JsonNode policy(String algorithm, String limit, String window) {
        return j("{\"algorithm\":" + algorithm + ",\"limit\":" + limit + ",\"windowSeconds\":" + window + "}");
    }

    @Test
    void needsAnObjectBody() {
        assertThat(Validation.requireObject(j("{\"a\":1}")).get("a").asInt()).isEqualTo(1);
        assertThat(message(() -> Validation.requireObject(j("[\"a\"]")))).isEqualTo("request body must be a JSON object");
        assertThat(message(() -> Validation.requireObject(null))).isEqualTo("request body must be a JSON object");
    }

    @Test
    void acceptsTenantIdsFromTheSafeAlphabet() {
        for (String good : List.of("acme", "a1b", "big-co-2", "x".repeat(32))) {
            assertThat(Validation.tenantId(JSON.getNodeFactory().stringNode(good))).isEqualTo(good);
        }
    }

    @Test
    void rejectsTenantIdsThatCouldBreakARedisKey() {
        for (String bad : List.of("ab", "x".repeat(33), "Acme", "-acme", "acme-", "ac:me", "ac}me", "ac*me", "ac me", "äcme")) {
            assertThat(message(() -> Validation.tenantId(JSON.getNodeFactory().stringNode(bad))))
                    .isEqualTo("id must be 3-32 characters of a-z, 0-9 and inner hyphens");
        }
    }

    @Test
    void requiresATenantId() {
        assertThat(message(() -> Validation.tenantId(null))).isEqualTo("id is required");
        assertThat(message(() -> Validation.tenantId(j("42")))).isEqualTo("id is required");
        assertThat(Validation.isTenantId("acme")).isTrue();
        assertThat(Validation.isTenantId("ACME")).isFalse();
    }

    @Test
    void trimsAndLimitsTheName() {
        assertThat(Validation.tenantName(j("\"  Acme Corp \""))).isEqualTo("Acme Corp");
        assertThat(Validation.tenantName(j("\"" + "x".repeat(80) + "\""))).hasSize(80);
        assertThat(message(() -> Validation.tenantName(j("null")))).isEqualTo("name is required");
        assertThat(message(() -> Validation.tenantName(j("\"   \"")))).isEqualTo("name must be 1-80 characters");
        assertThat(message(() -> Validation.tenantName(j("\"" + "x".repeat(81) + "\""))))
                .isEqualTo("name must be 1-80 characters");
    }

    @Test
    void parsesAValidPolicy() {
        assertThat(Validation.policy(policy("\"SLIDING_WINDOW\"", "100", "60")))
                .isEqualTo(new Policy(Algorithm.SLIDING_WINDOW, 100, 60));
        assertThat(Validation.policy(policy("\"TOKEN_BUCKET\"", "100000", "86400")).limit()).isEqualTo(100_000);
        assertThat(Validation.policy(policy("\"TOKEN_BUCKET\"", "1", "1")).windowSeconds()).isEqualTo(1);
    }

    @Test
    void requiresAPolicyObject() {
        assertThat(message(() -> Validation.policy(null))).isEqualTo("policy is required");
        assertThat(message(() -> Validation.policy(j("\"TOKEN_BUCKET\"")))).isEqualTo("policy is required");
    }

    @Test
    void checksTheAlgorithm() {
        for (String algorithm : List.of("null", "\"token_bucket\"", "\"LEAKY_BUCKET\"", "1")) {
            assertThat(message(() -> Validation.policy(policy(algorithm, "1", "1"))))
                    .isEqualTo("policy.algorithm must be TOKEN_BUCKET or SLIDING_WINDOW");
        }
    }

    @Test
    void needsARealIntegerLimitInRange() {
        for (String limit : List.of("null", "0", "100001", "1.5", "\"10\"", "true", "99999999999999999999")) {
            assertThat(message(() -> Validation.policy(policy("\"TOKEN_BUCKET\"", limit, "60"))))
                    .isEqualTo("policy.limit must be an integer between 1 and 100000");
        }
    }

    @Test
    void needsARealIntegerWindowInRange() {
        for (String window : List.of("null", "0", "86401", "2.5", "\"60\"", "false")) {
            assertThat(message(() -> Validation.policy(policy("\"TOKEN_BUCKET\"", "10", window))))
                    .isEqualTo("policy.windowSeconds must be an integer between 1 and 86400");
        }
    }

    @Test
    void defaultsTheResourceAndChecksItsAlphabet() {
        assertThat(Validation.resource(null)).isEqualTo("default");
        assertThat(Validation.resource(j("null"))).isEqualTo("default");
        assertThat(Validation.resource(j("\"orders.create:v2_x-1\""))).isEqualTo("orders.create:v2_x-1");
        for (String bad : List.of("\"\"", "\"Orders\"", "\"a*b\"", "\"a{b}\"", "\"" + "x".repeat(65) + "\"", "7", "\"a b\"")) {
            assertThat(message(() -> Validation.resource(j(bad))))
                    .isEqualTo("resource must be 1-64 characters of a-z, 0-9, '.', '_', ':' and '-'");
        }
    }

    @Test
    void defaultsTheCostToOneAndBoundsIt() {
        assertThat(Validation.cost(null)).isEqualTo(1);
        assertThat(Validation.cost(j("1000"))).isEqualTo(1000);
        for (String bad : List.of("0", "1001", "2.5", "\"3\"", "true")) {
            assertThat(message(() -> Validation.cost(j(bad)))).isEqualTo("cost must be an integer between 1 and 1000");
        }
    }

    @Test
    void readsTheMinutesQueryParameter() {
        assertThat(Validation.minutes(null)).isEqualTo(60);
        assertThat(Validation.minutes("1")).isEqualTo(1);
        assertThat(Validation.minutes("120")).isEqualTo(120);
        for (String bad : List.of("0", "121", "-5", "1.5", "", "abc", "١٢", "99999999999")) {
            assertThat(message(() -> Validation.minutes(bad))).isEqualTo("minutes must be an integer between 1 and 120");
        }
    }
}
