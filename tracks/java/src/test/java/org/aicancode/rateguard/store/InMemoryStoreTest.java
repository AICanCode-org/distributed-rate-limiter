package org.aicancode.rateguard.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.aicancode.rateguard.domain.DuplicateException;
import org.aicancode.rateguard.domain.Models.Algorithm;
import org.aicancode.rateguard.domain.Models.Decision;
import org.aicancode.rateguard.domain.Models.Policy;
import org.aicancode.rateguard.domain.Models.Tenant;
import org.aicancode.rateguard.domain.Models.UsagePoint;
import org.aicancode.rateguard.domain.StoreUnavailableException;
import org.aicancode.rateguard.support.MutableClock;
import org.junit.jupiter.api.Test;

/**
 * M2: the in-memory store (given) runs your algorithms. These tests pin down what a store must do; RedisStore (M3)
 * must behave the same, which the integration tests check in Phase 4.
 */
class InMemoryStoreTest {

    private static final Policy TB = new Policy(Algorithm.TOKEN_BUCKET, 2, 60);
    private static final Policy SW = new Policy(Algorithm.SLIDING_WINDOW, 2, 60);
    private static final Instant NOW = Instant.parse("2026-10-02T09:30:00Z");

    private final MutableClock clock = new MutableClock();
    private final InMemoryStore store = new InMemoryStore(clock);

    private static Tenant tenant(String id, String keyHash) {
        return new Tenant(id, "Acme", TB, keyHash, "rg_000000", NOW, NOW);
    }

    @Test
    void createsFindsListsAndRefusesDuplicates() {
        store.createTenant(tenant("beta", "h2"));
        store.createTenant(tenant("acme", "h1"));
        assertThat(store.listTenants()).extracting(Tenant::id).containsExactly("acme", "beta");
        assertThat(store.findByKeyHash("h2")).get().extracting(Tenant::id).isEqualTo("beta");
        assertThat(store.findByKeyHash("nope")).isEmpty();
        assertThatThrownBy(() -> store.createTenant(tenant("acme", "h9"))).isInstanceOf(DuplicateException.class);
    }

    @Test
    void movesTheKeyOnSave() {
        Tenant t = tenant("acme", "h1");
        store.createTenant(t);
        store.saveTenant(new Tenant("acme", "Acme", TB, "h-new", "rg_111111", NOW, NOW), t.keyHash());
        assertThat(store.findByKeyHash("h1")).isEmpty();
        assertThat(store.findByKeyHash("h-new")).get().extracting(Tenant::id).isEqualTo("acme");
    }

    @Test
    void keepsBucketsPerTenantAndResource() {
        assertThat(store.check("acme", "a", TB, 2).allowed()).isTrue();
        assertThat(store.check("acme", "a", TB, 1).allowed()).isFalse();
        assertThat(store.check("acme", "b", TB, 1).allowed()).isTrue();
        assertThat(store.check("beta", "a", TB, 1).allowed()).isTrue();
    }

    @Test
    void keepsSeparateStatePerAlgorithm() {
        store.check("acme", "a", TB, 2);
        assertThat(store.check("acme", "a", SW, 2)).isEqualTo(new Decision(true, 0, 0, 120_000));
    }

    @Test
    void usesTheInjectedClock() {
        store.check("acme", "a", TB, 2);
        clock.advance(Duration.ofSeconds(30));
        assertThat(store.check("acme", "a", TB, 1).allowed()).isTrue();
    }

    @Test
    void countsAllowedAndDeniedPerMinute() {
        for (int i = 0; i < 3; i++) {
            store.check("acme", "a", TB, 1);
        }
        clock.advance(Duration.ofMinutes(2));
        store.check("acme", "a", TB, 1);
        List<UsagePoint> points = store.usage("acme", 3);
        assertThat(points).extracting(p -> List.of(p.allowed(), p.denied()))
                .containsExactly(List.of(2L, 1L), List.of(0L, 0L), List.of(1L, 0L));
        assertThat(points.getLast().minute()).isEqualTo(clock.millis() / 60_000);
    }

    @Test
    void resetsOnlyThatTenant() {
        store.check("acme", "a", TB, 2);
        store.check("acme-2", "a", TB, 2);
        assertThat(store.reset("acme")).isEqualTo(1);
        assertThat(store.check("acme", "a", TB, 1).allowed()).isTrue();
        assertThat(store.check("acme-2", "a", TB, 1).allowed()).isFalse();
    }

    @Test
    void deletesTheTenantItsKeyAndItsState() {
        Tenant t = tenant("acme", "h1");
        store.createTenant(t);
        store.check("acme", "a", TB, 2);
        store.deleteTenant(t);
        assertThat(store.getTenant("acme")).isEmpty();
        assertThat(store.findByKeyHash("h1")).isEmpty();
        assertThat(store.check("acme", "a", TB, 1).remaining()).isEqualTo(1);
    }

    @Test
    void throwsStoreUnavailableDuringAnOutage() {
        store.up = false;
        assertThat(store.ping()).isFalse();
        assertThatThrownBy(() -> store.check("acme", "a", TB, 1)).isInstanceOf(StoreUnavailableException.class);
        assertThatThrownBy(() -> store.getTenant("acme")).isInstanceOf(StoreUnavailableException.class);
    }
}
