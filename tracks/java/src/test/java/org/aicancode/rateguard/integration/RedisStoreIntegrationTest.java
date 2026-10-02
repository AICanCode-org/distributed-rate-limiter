package org.aicancode.rateguard.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.aicancode.rateguard.domain.DuplicateException;
import org.aicancode.rateguard.domain.Models.Algorithm;
import org.aicancode.rateguard.config.AppConfig;
import org.aicancode.rateguard.domain.Models.Decision;
import org.aicancode.rateguard.domain.Models.FailMode;
import org.aicancode.rateguard.domain.Models.Policy;
import org.aicancode.rateguard.domain.Models.Tenant;
import org.aicancode.rateguard.domain.Models.UsagePoint;
import org.aicancode.rateguard.domain.StoreUnavailableException;
import org.aicancode.rateguard.limiter.Algorithms;
import org.aicancode.rateguard.limiter.Algorithms.BucketState;
import org.aicancode.rateguard.limiter.Algorithms.WindowState;
import org.aicancode.rateguard.metrics.Metrics;
import org.aicancode.rateguard.store.GuardedStore;
import org.aicancode.rateguard.store.RedisStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Phase 4: the RedisStore and the shared Lua scripts against a real Redis (docker compose up -d redis).
 * Run with: mvn verify -Pintegration. Uses database 15 of TEST_REDIS_URL and FLUSHDB before each test, so never point
 * it at data you care about.
 */
@Tag("integration")
class RedisStoreIntegrationTest {

    static final String URL = env("TEST_REDIS_URL", "redis://localhost:6379/15");
    static final Path LUA = Path.of(env("LUA_DIR", "../../spec/lua"));
    static final Instant NOW = Instant.parse("2026-10-02T09:30:00Z");
    static final Policy TB5 = new Policy(Algorithm.TOKEN_BUCKET, 5, 60);

    private final List<RedisStore> opened = new ArrayList<>();
    private RedisStore store;

    static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private RedisStore open() {
        RedisStore s = RedisStore.fromUrl(URL, 2000, LUA);
        opened.add(s);
        return s;
    }

    private static Tenant tenant(String id, String keyHash) {
        return new Tenant(id, "Acme Corp", TB5, keyHash, "rg_000000", NOW, NOW);
    }

    @BeforeEach
    void flush() {
        store = open();
        store.commands().flushdb();
    }

    @AfterEach
    void close() {
        opened.forEach(RedisStore::close);
    }

    @Test
    void pings() {
        assertThat(store.ping()).isTrue();
    }

    @Test
    void tenantRoundTrip() {
        Tenant t = tenant("acme", "h1");
        store.createTenant(t);
        assertThat(store.getTenant("acme")).contains(t);
        assertThat(store.findByKeyHash("h1")).contains(t);
        assertThat(store.findByKeyHash("nope")).isEmpty();
        Tenant rotated = new Tenant("acme", "Acme Corp", new Policy(Algorithm.SLIDING_WINDOW, 9, 10), "h2", "rg_111111",
                NOW, NOW);
        store.saveTenant(rotated, "h1");
        assertThat(store.findByKeyHash("h1")).isEmpty();
        assertThat(store.findByKeyHash("h2")).contains(rotated);
        store.createTenant(tenant("beta", "h3"));
        assertThat(store.listTenants()).extracting(Tenant::id).containsExactly("acme", "beta");
        store.deleteTenant(rotated);
        assertThat(store.getTenant("acme")).isEmpty();
        assertThat(store.findByKeyHash("h2")).isEmpty();
        assertThat(store.commands().sismember("rg:tenants", "acme")).isFalse();
    }

    @Test
    void duplicateIdsRaceAndExactlyOneWins() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                String hash = "h" + i;
                results.add(pool.submit(() -> {
                    try {
                        store.createTenant(tenant("acme", hash));
                        return true;
                    } catch (DuplicateException e) {
                        return false;
                    }
                }));
            }
            int wins = 0;
            for (Future<Boolean> f : results) {
                wins += f.get() ? 1 : 0;
            }
            assertThat(wins).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void luaAndThePureFunctionAgreeStepByStep(Algorithm algorithm) {
        Random rng = new Random(42);
        long[] steps = {0, 0, 1, 37, 250, 999, 1500, 4000};
        int[] costs = {1, 1, 1, 2, 7};
        Policy policy = new Policy(algorithm, 7, 3);
        long now = 1_790_933_400_000L;
        BucketState bucket = null;
        WindowState window = null;
        for (int i = 0; i < 400; i++) {
            now += steps[rng.nextInt(steps.length)];
            int cost = costs[rng.nextInt(costs.length)];
            Decision expected;
            if (algorithm == Algorithm.TOKEN_BUCKET) {
                var r = Algorithms.tokenBucket(bucket, 7, 3000, cost, now);
                bucket = r.state();
                expected = r.decision();
            } else {
                var r = Algorithms.slidingWindow(window, 7, 3000, cost, now);
                window = r.state();
                expected = r.decision();
            }
            assertThat(store.check("acme", "x", policy, cost, now)).as(now + " " + cost).isEqualTo(expected);
        }
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void concurrentChecksNeverExceedTheLimit(Algorithm algorithm) throws Exception {
        Policy policy = new Policy(algorithm, 10, 60);
        ExecutorService pool = Executors.newFixedThreadPool(25);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                RedisStore s = i % 2 == 0 ? store : open(); // several connections, like several app instances
                calls.add(() -> s.check("acme", "x", policy, 1).allowed());
            }
            long allowed = 0;
            for (Future<Boolean> f : pool.invokeAll(calls)) {
                allowed += f.get() ? 1 : 0;
            }
            assertThat(allowed).isEqualTo(10);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void twoAppInstancesShareOneLimit() {
        RedisStore other = open(); // its own connection, like a second container
        Policy policy = new Policy(Algorithm.TOKEN_BUCKET, 6, 60);
        int allowed = 0;
        for (int i = 0; i < 12; i++) {
            allowed += (i % 2 == 0 ? other : store).check("acme", "x", policy, 1).allowed() ? 1 : 0;
        }
        assertThat(allowed).isEqualTo(6);
    }

    @Test
    void keysHaveATtlAndUsageIsRecorded() {
        store.check("acme", "x", new Policy(Algorithm.TOKEN_BUCKET, 5, 60), 1);
        store.check("acme", "y", new Policy(Algorithm.SLIDING_WINDOW, 5, 60), 1);
        assertThat(store.commands().pttl("rg:{acme}:tb:x")).isBetween(1L, 13_000L); // full again in 12 s (+1 s)
        assertThat(store.commands().pttl("rg:{acme}:sw:y")).isBetween(1L, 121_000L);
        assertThat(store.commands().pttl("rg:{acme}:usage")).isBetween(1L, 10_800_000L);
        UsagePoint last = store.usage("acme", 2).getLast();
        assertThat(List.of(last.allowed(), last.denied())).isEqualTo(List.of(2L, 0L));
    }

    @Test
    void usageReadsTheRingForTheCurrentMinutes() {
        Policy policy = new Policy(Algorithm.TOKEN_BUCKET, 1, 3600);
        for (int i = 0; i < 3; i++) {
            store.check("acme", "x", policy, 1);
        }
        List<UsagePoint> points = store.usage("acme", 120);
        assertThat(points).hasSize(120);
        assertThat(List.of(points.getLast().allowed(), points.getLast().denied())).isEqualTo(List.of(1L, 2L));
        assertThat(points.subList(0, 119).stream().mapToLong(p -> p.allowed() + p.denied()).sum()).isZero();
        assertThat(points.getLast().minute() - points.getFirst().minute()).isEqualTo(119);
    }

    @Test
    void resetForgetsOneTenantOnly() {
        Policy policy = new Policy(Algorithm.TOKEN_BUCKET, 1, 3600);
        store.check("acme", "x", policy, 1);
        store.check("acme-2", "x", policy, 1);
        assertThat(store.reset("acme")).isEqualTo(1);
        assertThat(store.check("acme", "x", policy, 1).allowed()).isTrue();
        assertThat(store.check("acme-2", "x", policy, 1).allowed()).isFalse();
        assertThat(store.commands().exists("rg:{acme}:usage")).isEqualTo(1); // history survives a reset
    }

    @Test
    void theRedisClockIsUsedWithoutAnOverride() throws Exception {
        Policy policy = new Policy(Algorithm.TOKEN_BUCKET, 2, 1);
        List<Boolean> out = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            out.add(store.check("acme", "x", policy, 1).allowed());
        }
        assertThat(out).containsExactly(true, true, false);
        Thread.sleep(600);
        assertThat(store.check("acme", "x", policy, 1).allowed()).isTrue();
    }

    @Test
    void scriptsAreReloadedAfterAScriptFlush() {
        Policy policy = new Policy(Algorithm.SLIDING_WINDOW, 3, 60);
        store.check("acme", "x", policy, 1);
        store.commands().scriptFlush();
        assertThat(store.check("acme", "x", policy, 1).remaining()).isEqualTo(1);
    }

    @Test
    void anUnreachableRedisIsStoreUnavailable() {
        RedisStore dead = RedisStore.fromUrl("redis://127.0.0.1:1/0", 300, LUA);
        opened.add(dead);
        assertThat(dead.ping()).isFalse();
        assertThatThrownBy(() -> dead.check("acme", "x", new Policy(Algorithm.TOKEN_BUCKET, 1, 1), 1))
                .isInstanceOf(StoreUnavailableException.class);
        assertThatThrownBy(() -> dead.getTenant("acme")).isInstanceOf(StoreUnavailableException.class);
    }

    // ---- v1.1 (Phase 7) ----------------------------------------------------------------------------
    @Test
    void failModeIsStoredAndV10HashesReadAsClosed() {
        store.createTenant(new Tenant("acme", "Acme Corp", new Policy(Algorithm.TOKEN_BUCKET, 5, 60, FailMode.LOCAL),
                "h1", "rg_000000", NOW, NOW));
        assertThat(store.getTenant("acme").orElseThrow().policy().failMode()).isEqualTo(FailMode.LOCAL);
        store.commands().hdel("rg:tenant:acme", "failMode"); // what a tenant written by v1.0 looks like
        assertThat(store.getTenant("acme").orElseThrow().policy().failMode()).isEqualTo(FailMode.CLOSED);
    }

    @Test
    void aDeadRedisCostsThreeFailuresThenAnswersFast() {
        Metrics metrics = new Metrics();
        RedisStore dead = RedisStore.fromUrl("redis://127.0.0.1:1/0", 300, LUA);
        opened.add(dead);
        GuardedStore guarded = AppConfig.guard(dead, 5000, metrics, System::currentTimeMillis);
        long started = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            assertThat(guarded.ping()).isFalse();
        }
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(5000);
        assertThat(metrics.value(Metrics.STORE_ERRORS)).isEqualTo(3);
        assertThat(metrics.value(Metrics.STORE_SHORT_CIRCUITS)).isEqualTo(17);
    }
}
