package org.aicancode.rateguard.store;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.aicancode.rateguard.domain.DuplicateException;
import org.aicancode.rateguard.domain.Models.Algorithm;
import org.aicancode.rateguard.domain.Models.Decision;
import org.aicancode.rateguard.domain.Models.Policy;
import org.aicancode.rateguard.domain.Models.Tenant;
import org.aicancode.rateguard.domain.Models.UsagePoint;
import org.aicancode.rateguard.domain.StoreUnavailableException;

/**
 * M3: the real store on Redis (Lettuce, synchronous API). Limiter checks are ONE Lua script call each (atomic, one
 * round trip).
 *
 * <p>Lettuce multiplexes one connection over all threads, which is fast and safe for single commands and scripts. A
 * MULTI/EXEC transaction on a shared connection would swallow other threads' commands, so transactions use their own
 * connection, one at a time.
 */
public class RedisStore implements Store, AutoCloseable {

    private final RedisClient client;
    private final String tokenBucketLua;
    private final String slidingWindowLua;
    private final Map<String, String> shas = new LinkedHashMap<>();
    private final Object connectLock = new Object();
    private final Object txLock = new Object();
    private volatile StatefulRedisConnection<String, String> shared;
    private StatefulRedisConnection<String, String> tx;

    public RedisStore(RedisClient client, Path luaDir) {
        this.client = client;
        this.tokenBucketLua = read(luaDir.resolve("token_bucket.lua"));
        this.slidingWindowLua = read(luaDir.resolve("sliding_window.lua"));
    }

    public static RedisStore fromUrl(String url, int timeoutMs, Path luaDir) {
        RedisURI uri = RedisURI.create(url);
        Duration timeout = Duration.ofMillis(timeoutMs);
        uri.setTimeout(timeout);
        RedisClient client = RedisClient.create(uri);
        client.setOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                .timeoutOptions(TimeoutOptions.enabled(timeout))
                // fail fast: while disconnected, commands fail at once instead of queueing up
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
        return new RedisStore(client, luaDir);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read Lua script " + file, e);
        }
    }

    /** Connects lazily, so the app starts even while Redis is down (/health then says DOWN). */
    private RedisCommands<String, String> redis() {
        synchronized (connectLock) {
            if (shared == null) {
                shared = client.connect();
            }
            return shared.sync();
        }
    }

    private <T> T call(Function<RedisCommands<String, String>, T> fn) {
        try {
            return fn.apply(redis());
        } catch (DuplicateException e) {
            throw e;
        } catch (RedisException e) {
            throw new StoreUnavailableException(String.valueOf(e.getMessage()), e);
        }
    }

    /** MULTI ... EXEC on the transaction connection: all or nothing. */
    private void transaction(Consumer<RedisCommands<String, String>> commands) {
        synchronized (txLock) {
            runTransaction(commands);
        }
    }

    private void runTransaction(Consumer<RedisCommands<String, String>> commands) {
        try {
            if (tx == null) {
                tx = client.connect();
            }
            RedisCommands<String, String> r = tx.sync();
            r.multi();
            try {
                commands.accept(r);
                r.exec();
            } catch (RuntimeException e) {
                r.discard();
                throw e;
            }
        } catch (RedisException e) {
            throw new StoreUnavailableException(String.valueOf(e.getMessage()), e);
        }
    }

    @Override
    public boolean ping() {
        try {
            return "PONG".equals(redis().ping());
        } catch (RedisException e) {
            return false;
        }
    }

    static Map<String, String> toHash(Tenant t) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("id", t.id());
        h.put("name", t.name());
        h.put("algorithm", t.policy().algorithm().name());
        h.put("limit", Integer.toString(t.policy().limit()));
        h.put("windowSeconds", Integer.toString(t.policy().windowSeconds()));
        h.put("keyHash", t.keyHash());
        h.put("keyPrefix", t.keyPrefix());
        h.put("createdAt", Long.toString(t.createdAt().toEpochMilli()));
        h.put("updatedAt", Long.toString(t.updatedAt().toEpochMilli()));
        return h;
    }

    static Tenant fromHash(Map<String, String> h) {
        Policy policy = new Policy(Algorithm.valueOf(h.get("algorithm")), Integer.parseInt(h.get("limit")),
                Integer.parseInt(h.get("windowSeconds")));
        return new Tenant(h.get("id"), h.get("name"), policy, h.get("keyHash"), h.get("keyPrefix"),
                Instant.ofEpochMilli(Long.parseLong(h.get("createdAt"))),
                Instant.ofEpochMilli(Long.parseLong(h.get("updatedAt"))));
    }

    @Override
    public void createTenant(Tenant tenant) {
        // SADD answers 0 when the id is already a member: Redis, not the app, decides the race.
        if (call(r -> r.sadd(Keys.TENANTS, tenant.id())) == 0) {
            throw new DuplicateException(tenant.id());
        }
        transaction(r -> {
            r.hset(Keys.tenant(tenant.id()), toHash(tenant));
            r.set(Keys.apiKey(tenant.keyHash()), tenant.id());
        });
    }

    @Override
    public Optional<Tenant> getTenant(String tenantId) {
        Map<String, String> h = call(r -> r.hgetall(Keys.tenant(tenantId)));
        return h.isEmpty() ? Optional.empty() : Optional.of(fromHash(h));
    }

    @Override
    public List<Tenant> listTenants() {
        return call(r -> r.smembers(Keys.TENANTS).stream().sorted()
                .map(id -> r.hgetall(Keys.tenant(id)))
                .filter(h -> !h.isEmpty())
                .map(RedisStore::fromHash)
                .toList());
    }

    @Override
    public Optional<Tenant> findByKeyHash(String keyHash) {
        String id = call(r -> r.get(Keys.apiKey(keyHash)));
        return id == null ? Optional.empty() : getTenant(id);
    }

    @Override
    public void saveTenant(Tenant tenant, String oldKeyHash) {
        transaction(r -> {
            r.hset(Keys.tenant(tenant.id()), toHash(tenant));
            if (!oldKeyHash.equals(tenant.keyHash())) {
                r.del(Keys.apiKey(oldKeyHash));
                r.set(Keys.apiKey(tenant.keyHash()), tenant.id());
            }
        });
    }

    @Override
    public void deleteTenant(Tenant tenant) {
        transaction(r -> {
            r.del(Keys.tenant(tenant.id()), Keys.apiKey(tenant.keyHash()), Keys.usage(tenant.id()));
            r.srem(Keys.TENANTS, tenant.id());
        });
        reset(tenant.id());
    }

    @Override
    public int reset(String tenantId) {
        return call(r -> {
            // SCAN walks the keyspace in small steps; KEYS would block Redis for everyone.
            Set<String> doomed = new LinkedHashSet<>();
            ScanArgs args = ScanArgs.Builder.matches(Keys.tenantPattern(tenantId)).limit(500);
            ScanCursor cursor = ScanCursor.INITIAL;
            do {
                KeyScanCursor<String> page = r.scan(cursor, args);
                page.getKeys().stream().filter(k -> !k.equals(Keys.usage(tenantId))).forEach(doomed::add);
                cursor = page;
            } while (!cursor.isFinished());
            return doomed.isEmpty() ? 0 : r.del(doomed.toArray(String[]::new)).intValue();
        });
    }

    @Override
    public Decision check(String tenantId, String resource, Policy policy, int cost) {
        return check(tenantId, resource, policy, cost, null);
    }

    /** {@code nowMs} replaces the Redis clock (tests only, so they are deterministic). */
    public Decision check(String tenantId, String resource, Policy policy, int cost, Long nowMs) {
        String script = policy.algorithm() == Algorithm.TOKEN_BUCKET ? tokenBucketLua : slidingWindowLua;
        String[] keys = {Keys.limiter(tenantId, policy.algorithm(), resource), Keys.usage(tenantId)};
        List<String> args = new ArrayList<>(List.of(Integer.toString(policy.limit()), Long.toString(policy.windowMs()),
                Integer.toString(cost)));
        if (nowMs != null) {
            args.add(Long.toString(nowMs));
        }
        List<Long> out = call(r -> evalsha(r, script, keys, args.toArray(String[]::new)));
        return new Decision(out.get(0) == 1, out.get(1), out.get(2), out.get(3));
    }

    /** EVALSHA, and EVAL (which also caches the script) when Redis answers NOSCRIPT, e.g. after a restart. */
    private List<Long> evalsha(RedisCommands<String, String> r, String script, String[] keys, String[] args) {
        String sha;
        synchronized (shas) {
            sha = shas.computeIfAbsent(script, r::digest);
        }
        try {
            return r.evalsha(sha, ScriptOutputType.MULTI, keys, args);
        } catch (RedisNoScriptException e) {
            return r.eval(script, ScriptOutputType.MULTI, keys, args);
        }
    }

    @Override
    public List<UsagePoint> usage(String tenantId, int minutes) {
        return call(r -> {
            List<String> time = r.time();
            long nowMs = Long.parseLong(time.get(0)) * 1000 + Long.parseLong(time.get(1)) / 1000;
            long current = Math.floorDiv(nowMs, 60_000L);
            Map<String, String> ring = r.hgetall(Keys.usage(tenantId));
            List<UsagePoint> out = new ArrayList<>();
            for (long minute = current - minutes + 1; minute <= current; minute++) {
                int slot = Keys.usageSlot(minute);
                if (Long.toString(minute).equals(ring.get("m" + slot))) {
                    out.add(new UsagePoint(minute, Long.parseLong(ring.getOrDefault("a" + slot, "0")),
                            Long.parseLong(ring.getOrDefault("d" + slot, "0"))));
                } else {
                    out.add(new UsagePoint(minute, 0, 0));
                }
            }
            return out;
        });
    }

    /** The raw commands (integration tests inspect TTLs and flush the test database). */
    public RedisCommands<String, String> commands() {
        return redis();
    }

    @Override
    public void close() {
        synchronized (connectLock) {
            if (shared != null) {
                shared.close();
            }
        }
        synchronized (txLock) {
            if (tx != null) {
                tx.close();
            }
        }
        client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
}
