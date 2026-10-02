package org.aicancode.rateguard.service;

import static org.aicancode.rateguard.support.Problems.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.aicancode.rateguard.domain.Models.Algorithm;
import org.aicancode.rateguard.domain.Models.Policy;
import org.aicancode.rateguard.domain.Models.Tenant;
import org.aicancode.rateguard.security.ApiKeys;
import org.aicancode.rateguard.service.TenantService.Created;
import org.aicancode.rateguard.store.InMemoryStore;
import org.aicancode.rateguard.support.MutableClock;
import org.aicancode.rateguard.support.TestApp;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** M4: the tenant service (framework-free business logic behind the admin API). */
class TenantServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BODY = "{\"id\":\"acme\",\"name\":\"Acme Corp\",\"policy\":"
            + "{\"algorithm\":\"TOKEN_BUCKET\",\"limit\":5,\"windowSeconds\":60}}";

    private final MutableClock clock = new MutableClock();
    private final InMemoryStore store = new InMemoryStore(clock);
    private final TenantService svc = new TenantService(store, clock, TestApp.keyGen());

    private static JsonNode j(String json) {
        return JSON.readTree(json);
    }

    @Test
    void createReturnsTheKeyOnceAndStoresOnlyItsHash() {
        Created c = svc.create(j(BODY));
        assertThat(c.apiKey()).isEqualTo("rg_00000000000000000000000000000001");
        assertThat(c.tenant().keyHash()).isEqualTo(ApiKeys.hash(c.apiKey()));
        assertThat(c.tenant().keyPrefix()).isEqualTo("rg_000000");
        assertThat(c.tenant().policy()).isEqualTo(new Policy(Algorithm.TOKEN_BUCKET, 5, 60));
        assertThat(store.listTenants().toString()).doesNotContain(c.apiKey());
    }

    @Test
    void createValidatesEverything() {
        assertThat(statusOf(() -> svc.create(j("[\"x\"]")))).isEqualTo(List.of(400, "validation-error"));
        assertThat(statusOf(() -> svc.create(j(BODY.replace("\"acme\"", "\"A\""))))).isEqualTo(List.of(400, "validation-error"));
        assertThat(statusOf(() -> svc.create(j("{\"id\":\"acme\",\"name\":\"A\",\"policy\":null}"))))
                .isEqualTo(List.of(400, "validation-error"));
    }

    @Test
    void duplicateIdIs409() {
        svc.create(j(BODY));
        assertThat(statusOf(() -> svc.create(j(BODY)))).isEqualTo(List.of(409, "tenant-exists"));
    }

    @Test
    void unknownOrImpossibleIdsAre404() {
        assertThat(statusOf(() -> svc.get("nobody"))).isEqualTo(List.of(404, "not-found"));
        assertThat(statusOf(() -> svc.get("NOT A VALID ID"))).isEqualTo(List.of(404, "not-found"));
    }

    @Test
    void setPolicyReplacesThePolicyAndBumpsUpdatedAt() {
        svc.create(j(BODY));
        clock.advance(Duration.ofMinutes(5));
        Tenant t = svc.setPolicy("acme", j("{\"algorithm\":\"SLIDING_WINDOW\",\"limit\":50,\"windowSeconds\":10}"));
        assertThat(t.policy()).isEqualTo(new Policy(Algorithm.SLIDING_WINDOW, 50, 10));
        assertThat(t.updatedAt()).isAfter(t.createdAt());
        assertThat(svc.get("acme").policy().limit()).isEqualTo(50);
    }

    @Test
    void rotateKeyInvalidatesTheOldKey() {
        String old = svc.create(j(BODY)).apiKey();
        String fresh = svc.rotateKey("acme").apiKey();
        assertThat(fresh).isNotEqualTo(old);
        assertThat(svc.authenticate(fresh).id()).isEqualTo("acme");
        assertThat(statusOf(() -> svc.authenticate(old))).isEqualTo(List.of(401, "invalid-api-key"));
    }

    @Test
    void authenticateRejectsMissingMalformedAndUnknownKeys() {
        svc.create(j(BODY));
        store.up = false; // malformed keys must not even reach the store
        for (String bad : Arrays.asList(null, "", "Bearer x", "rg_short")) {
            assertThat(statusOf(() -> svc.authenticate(bad))).isEqualTo(List.of(401, "invalid-api-key"));
        }
        store.up = true;
        assertThat(statusOf(() -> svc.authenticate("rg_" + "f".repeat(32)))).isEqualTo(List.of(401, "invalid-api-key"));
    }

    @Test
    void deleteAndReset() {
        svc.create(j(BODY));
        store.check("acme", "x", new Policy(Algorithm.TOKEN_BUCKET, 5, 60), 5);
        assertThat(svc.reset("acme")).isEqualTo(1);
        svc.delete("acme");
        assertThat(statusOf(() -> svc.get("acme"))).isEqualTo(List.of(404, "not-found"));
        assertThat(statusOf(() -> svc.reset("acme"))).isEqualTo(List.of(404, "not-found"));
    }

    @Test
    void usageValidatesMinutes() {
        svc.create(j(BODY));
        TenantService.Usage u = svc.usage("acme", null);
        assertThat(u.minutes()).isEqualTo(60);
        assertThat(u.points()).hasSize(60);
        assertThat(u.tenant().id()).isEqualTo("acme");
        assertThat(statusOf(() -> svc.usage("acme", "500"))).isEqualTo(List.of(400, "validation-error"));
    }
}
