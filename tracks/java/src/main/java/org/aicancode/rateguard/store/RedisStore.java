package org.aicancode.rateguard.store;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.RedisURI;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.aicancode.rateguard.domain.DuplicateException;
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
        // TODO(phase-3 M3): read(luaDir.resolve("token_bucket.lua")) and sliding_window.lua (you wrote them in
        //   Phase 2). Until then the app starts without them.
        this.tokenBucketLua = null;
        this.slidingWindowLua = null;
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
        // TODO(phase-3 M3): the tenant as a flat string map for HSET: id, name, algorithm, limit,
        //   windowSeconds, keyHash, keyPrefix, createdAt/updatedAt as epoch ms.
        throw new UnsupportedOperationException("TODO(phase-3 M3): RedisStore.toHash");
    }

    static Tenant fromHash(Map<String, String> h) {
        // TODO(phase-3 M3): the reverse of toHash (numbers parsed, epoch ms back to Instants).
        throw new UnsupportedOperationException("TODO(phase-3 M3): RedisStore.fromHash");
    }

    @Override
    public void createTenant(Tenant tenant) {
        // TODO(phase-3 M3): SADD rg:tenants <id>; 0 means the id exists -> DuplicateError (Redis decides the
        //   race, not the app). Then in ONE MULTI/EXEC: HSET the tenant hash and SET rg:key:<hash> <id>.
        throw new UnsupportedOperationException("TODO(phase-3 M3): RedisStore.createTenant");
    }

    @Override
    public Optional<Tenant> getTenant(String tenantId) {
        // TODO(phase-3 M3): HGETALL rg:tenant:<id>; empty -> null.
        throw new UnsupportedOperationException("TODO(phase-3 M3): RedisStore.getTenant");
    }

    @Override
    public List<Tenant> listTenants() {
        // TODO(phase-3 M3): SMEMBERS rg:tenants, sorted by id, then HGETALL each one in a pipeline.
        throw new UnsupportedOperationException("TODO(phase-3 M3): RedisStore.listTenants");
    }

    @Override
    public Optional<Tenant> findByKeyHash(String keyHash) {
        // TODO(phase-3 M3): GET rg:key:<hash> -> tenant id -> get_tenant; unknown -> null.
        throw new UnsupportedOperationException("TODO(phase-3 M3): RedisStore.findByKeyHash");
    }

    @Override
    public void saveTenant(Tenant tenant, String oldKeyHash) {
        // TODO(phase-3 M3): ONE MULTI/EXEC: HSET the hash; if the key hash changed, DEL the old rg:key:<old>
        //   and SET the new one (the old key stops working at once).
        throw new UnsupportedOperationException("TODO(phase-3 M3): RedisStore.saveTenant");
    }

    @Override
    public void deleteTenant(Tenant tenant) {
        // TODO(phase-3 M3): ONE MULTI/EXEC: DEL the tenant hash, its rg:key and its usage ring, SREM from
        //   rg:tenants; then reset() the limiter keys.
        throw new UnsupportedOperationException("TODO(phase-3 M3): RedisStore.deleteTenant");
    }

    @Override
    public int reset(String tenantId) {
        // TODO(phase-3 M3): SCAN (never KEYS) for tenant_pattern, keep the usage ring, DEL the rest; return
        //   how many were deleted.
        throw new UnsupportedOperationException("TODO(phase-3 M3): RedisStore.reset");
    }

    @Override
    public Decision check(String tenantId, String resource, Policy policy, int cost) {
        // TODO(phase-3 M3): ONE script call (EVALSHA, EVAL on NOSCRIPT): token_bucket.lua or
        //   sliding_window.lua with KEYS = [limiter key, usage key] and ARGV = [limit, window_ms, cost (,
        //   now_ms for tests)]; it returns [allowed 0/1, remaining, retryAfterMs, resetAfterMs].
        throw new UnsupportedOperationException("TODO(phase-3 M3): RedisStore.check");
    }

    /** {@code nowMs} replaces the Redis clock (tests only, so they are deterministic). */
    public Decision check(String tenantId, String resource, Policy policy, int cost, Long nowMs) {
        // TODO(phase-3 M3): ONE script call (EVALSHA, EVAL on NOSCRIPT): token_bucket.lua or
        //   sliding_window.lua with KEYS = [limiter key, usage key] and ARGV = [limit, window_ms, cost (,
        //   now_ms for tests)]; it returns [allowed 0/1, remaining, retryAfterMs, resetAfterMs].
        throw new UnsupportedOperationException("TODO(phase-3 M3): RedisStore.check");
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
        // TODO(phase-3 M3): TIME gives Redis' clock; HGETALL the ring; for each of the last n minutes slot =
        //   usage_slot(minute): use a<slot>/d<slot> only if m<slot> == minute, else zeros. Oldest first.
        throw new UnsupportedOperationException("TODO(phase-3 M3): RedisStore.usage");
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
