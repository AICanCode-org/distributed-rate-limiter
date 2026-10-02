package org.aicancode.rateguard.service;

import static org.aicancode.rateguard.support.Problems.problem;
import static org.aicancode.rateguard.support.Problems.statusOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.aicancode.rateguard.domain.ApiException;
import org.aicancode.rateguard.domain.Models.Algorithm;
import org.aicancode.rateguard.domain.Models.Policy;
import org.aicancode.rateguard.domain.Models.Tenant;
import org.aicancode.rateguard.domain.Models.Verdict;
import org.aicancode.rateguard.store.InMemoryStore;
import org.aicancode.rateguard.support.MutableClock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** M4: the limiter service: checks, costs, headers and the fail-closed rule (ADR-0004). */
class LimiterServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-02T09:30:00Z");

    private final InMemoryStore store = new InMemoryStore(new MutableClock());
    private final LimiterService limiter = new LimiterService(store);

    private static Tenant tenant(int limit, int window) {
        return new Tenant("acme", "Acme Corp", new Policy(Algorithm.TOKEN_BUCKET, limit, window), "h", "rg_000000", NOW, NOW);
    }

    private static Tenant tenant() {
        return tenant(3, 60);
    }

    private static JsonNode j(String json) {
        return JSON.readTree(json);
    }

    private static ApiException thrown(Runnable call) {
        ApiException[] caught = new ApiException[1];
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> caught[0] = e);
        return caught[0];
    }

    @Test
    void checkDefaultsToResourceDefaultAndCostOne() {
        Verdict v = limiter.check(tenant(), null);
        assertThat(List.of(v.resource(), v.cost(), v.decision().allowed(), v.decision().remaining()))
                .isEqualTo(List.of("default", 1, true, 2L));
    }

    @Test
    void checkReadsResourceAndCost() {
        Verdict v = limiter.check(tenant(), j("{\"resource\":\"orders.create\",\"cost\":3}"));
        assertThat(v.resource()).isEqualTo("orders.create");
        assertThat(v.decision().remaining()).isZero();
        assertThat(limiter.check(tenant(), j("{\"resource\":\"orders.create\"}")).decision().allowed()).isFalse();
        assertThat(limiter.check(tenant(), j("{\"resource\":\"search\"}")).decision().allowed()).isTrue();
    }

    @Test
    void checkValidatesTheBody() {
        for (String body : List.of("[\"x\"]", "{\"cost\":0}", "{\"resource\":\"A\"}")) {
            assertThat(statusOf(() -> limiter.check(tenant(), j(body))).get(0)).isEqualTo(400);
        }
    }

    @Test
    void aCostAboveTheLimitCanNeverPassSoItIs422() {
        assertThat(problem(() -> limiter.check(tenant(3, 60), j("{\"cost\":4}"))))
                .isEqualTo(List.of(422, "cost-exceeds-limit", "cost 4 is more than the limit of 3"));
    }

    @Test
    void enforceThrows429WithRetryAfter() {
        for (int i = 0; i < 3; i++) {
            limiter.enforce(tenant());
        }
        ApiException e = thrown(() -> limiter.enforce(tenant()));
        assertThat(List.of(e.status(), e.slug(), e.detail()))
                .isEqualTo(List.of(429, "rate-limited", "limit of 3 per 60 s reached, retry in 20 s"));
        assertThat(e.headers()).isEqualTo(Map.of("X-RateLimit-Limit", "3", "X-RateLimit-Remaining", "0",
                "X-RateLimit-Reset", "60", "Retry-After", "20"));
    }

    @Test
    void retryAfterIsAtLeastOneSecond() {
        Tenant t = tenant(1000, 1);
        for (int i = 0; i < 1000; i++) {
            limiter.enforce(t);
        }
        assertThat(thrown(() -> limiter.enforce(t)).headers().get("Retry-After")).isEqualTo("1"); // 1 ms rounds UP
    }

    @Test
    void aStoreOutageFailsClosedWith503() {
        store.up = false;
        ApiException e = thrown(() -> limiter.check(tenant(), null));
        assertThat(List.of(e.status(), e.slug())).isEqualTo(List.of(503, "limiter-unavailable"));
        assertThat(e.headers()).isEqualTo(Map.of("Retry-After", "1"));
    }

    @Test
    void headersRoundMillisecondsUp() {
        assertThat(List.of(LimiterService.secondsUp(0), LimiterService.secondsUp(1), LimiterService.secondsUp(1000),
                LimiterService.secondsUp(1001))).isEqualTo(List.of(0L, 1L, 1L, 2L));
    }

    @Test
    void rateLimitHeaders() {
        Verdict v = limiter.check(tenant(10, 60), j("{\"cost\":4}"));
        assertThat(LimiterService.headers(v)).isEqualTo(Map.of("X-RateLimit-Limit", "10", "X-RateLimit-Remaining", "6",
                "X-RateLimit-Reset", "24"));
    }
}
