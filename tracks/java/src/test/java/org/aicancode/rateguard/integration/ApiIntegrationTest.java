package org.aicancode.rateguard.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The real app (Spring context, real wiring) on a random port against a real Redis, over real HTTP. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTest {

    static final String ADMIN = "Bearer integration-admin-token-0123456789abcdef";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("REDIS_URL", () -> RedisStoreIntegrationTest.URL);
        registry.add("ADMIN_TOKEN", () -> ADMIN.substring("Bearer ".length()));
        registry.add("AI_PROVIDER", () -> "offline");
    }

    @Value("${local.server.port}")
    int port;

    record Res(int status, HttpResponse<String> raw, JsonNode json) {
    }

    @BeforeEach
    void flush() {
        RedisClient client = RedisClient.create(RedisStoreIntegrationTest.URL);
        try (var connection = client.connect()) {
            connection.sync().flushdb();
        } finally {
            client.shutdown();
        }
    }

    Res call(String method, String path, String body, String header, String value) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (header != null) {
            b.header(header, value);
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r, r.body().isEmpty() ? null : JSON.readTree(r.body()));
    }

    String tenant(String id, String algorithm, int limit) throws Exception {
        Res r = call("POST", "/api/v1/admin/tenants", "{\"id\":\"" + id + "\",\"name\":\"Acme Corp\",\"policy\":"
                + "{\"algorithm\":\"" + algorithm + "\",\"limit\":" + limit + ",\"windowSeconds\":60}}", "Authorization", ADMIN);
        assertThat(r.status()).as(String.valueOf(r.json())).isEqualTo(201);
        return r.json().get("apiKey").asString();
    }

    @Test
    void theWholeApiOnRedis() throws Exception {
        String key = tenant("acme", "TOKEN_BUCKET", 3);
        List<Integer> codes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            codes.add(call("GET", "/api/v1/demo/hello", null, "X-API-Key", key).status());
        }
        assertThat(codes).containsExactly(200, 200, 200, 429);
        Res usage = call("GET", "/api/v1/admin/tenants/acme/usage?minutes=1", null, "Authorization", ADMIN);
        assertThat(usage.json().get("totals").toString()).isEqualTo("{\"allowed\":3,\"denied\":1}");
        assertThat(call("GET", "/health", null, null, null).json().toString())
                .isEqualTo("{\"status\":\"UP\",\"checks\":{\"redis\":\"UP\"}}");
        Res check = call("POST", "/api/v1/limits/check", "{\"cost\":2}", "X-API-Key", key);
        assertThat(check.status()).isEqualTo(200);
        assertThat(check.raw().headers().firstValue("X-RateLimit-Limit")).contains("3");
        assertThat(call("POST", "/api/v1/admin/tenants/acme/advice", null, "Authorization", ADMIN).json()
                .get("provider").asString()).isEqualTo("rules");
        assertThat(call("GET", "/api/v1/admin/tenants", null, "Authorization", "Bearer wrong").status()).isEqualTo(401);
    }

    @Test
    void parallelHttpRequestsOnRedis() throws Exception {
        String key = tenant("acme", "SLIDING_WINDOW", 8);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                calls.add(() -> call("GET", "/api/v1/demo/hello", null, "X-API-Key", key).status());
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(calls)) {
                codes.add(f.get());
            }
            assertThat(codes.stream().filter(c -> c == 200).count()).isEqualTo(8);
            assertThat(codes.stream().filter(c -> c == 429).count()).isEqualTo(22);
        } finally {
            pool.shutdown();
        }
    }
}
