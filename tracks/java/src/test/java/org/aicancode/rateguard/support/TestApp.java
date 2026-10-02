package org.aicancode.rateguard.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.aicancode.rateguard.ai.AdvisorService;
import org.aicancode.rateguard.ai.RuleAdvisor;
import org.aicancode.rateguard.security.AdminAuth;
import org.aicancode.rateguard.service.LimiterService;
import org.aicancode.rateguard.service.TenantService;
import org.aicancode.rateguard.store.InMemoryStore;
import org.aicancode.rateguard.store.Store;
import org.aicancode.rateguard.web.AdminController;
import org.aicancode.rateguard.web.HealthController;
import org.aicancode.rateguard.web.LimitsController;
import org.aicancode.rateguard.web.ProblemHandler;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real controllers on the in-memory store (no Redis, no network, no Spring context), plus helpers that read like
 * the user stories. The same helpers exist in the Python (conftest.py) and TypeScript (helpers.ts) tracks.
 */
public class TestApp {

    public static final String ADMIN_TOKEN = "test-admin-token-at-least-32-characters";
    public static final String ADMIN = "Bearer " + ADMIN_TOKEN;
    public static final String KEY1 = "rg_00000000000000000000000000000001";
    public static final JsonMapper JSON = JsonMapper.builder().build();

    public final MutableClock clock = new MutableClock();
    /** The in-memory store (null when the app runs on another store, e.g. Redis in the integration tests). */
    public final InMemoryStore memory;
    public final Store store;
    public final MockMvc mvc;

    /** The app on {@code store} (null: its own in-memory store on the test clock). */
    public TestApp(Store store, AdvisorService advisor) {
        this.memory = store == null ? new InMemoryStore(clock) : null;
        this.store = store == null ? memory : store;
        TenantService tenants = new TenantService(this.store, clock, keyGen());
        mvc = MockMvcBuilders.standaloneSetup(new HealthController(this.store),
                        new AdminController(new AdminAuth(ADMIN_TOKEN), tenants, advisor, clock),
                        new LimitsController(tenants, new LimiterService(this.store)))
                .setControllerAdvice(new ProblemHandler())
                .build();
    }

    /** On its own in-memory store, offline advisor. */
    public TestApp() {
        this(null, offlineAdvisor());
    }

    public TestApp(AdvisorService advisor) {
        this(null, advisor);
    }

    public static AdvisorService offlineAdvisor() {
        return new AdvisorService(new RuleAdvisor(), null);
    }

    /** Predictable API keys: rg_000...001, rg_000...002, ... */
    public static Supplier<String> keyGen() {
        int[] next = {0};
        return () -> String.format("rg_%032x", ++next[0]);
    }

    /** A response: status, headers and the parsed JSON body (null when there is no body). */
    public record Res(int status, MockHttpServletResponse raw, String body, JsonNode json) {
        public String header(String name) {
            return raw.getHeader(name);
        }
    }

    public Res call(String method, String url, Object body, Map<String, String> headers) {
        try {
            MockHttpServletRequestBuilder b = request(HttpMethod.valueOf(method), url);
            if (body instanceof String raw) {
                b.contentType(MediaType.APPLICATION_JSON).content(raw);
            } else if (body != null) {
                b.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
            }
            headers.forEach(b::header);
            MockHttpServletResponse r = mvc.perform(b).andReturn().getResponse();
            String text = r.getContentAsString(StandardCharsets.UTF_8);
            return new Res(r.getStatus(), r, text, text.isEmpty() ? null : JSON.readTree(text));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Res get(String url) {
        return call("GET", url, null, Map.of());
    }

    public Res admin(String method, String url, Object body) {
        return call(method, url, body, Map.of("Authorization", ADMIN));
    }

    public Res admin(String method, String url) {
        return admin(method, url, null);
    }

    public static Map<String, Object> policy(String algorithm, int limit, int windowSeconds) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("algorithm", algorithm);
        p.put("limit", limit);
        p.put("windowSeconds", windowSeconds);
        return p;
    }

    /** Creates a tenant and returns the 201 body (with its apiKey). */
    public JsonNode tenant(String id, String algorithm, int limit, int windowSeconds) {
        Res r = admin("POST", "/api/v1/admin/tenants",
                Map.of("id", id, "name", "Acme Corp", "policy", policy(algorithm, limit, windowSeconds)));
        assertThat(r.status()).as(String.valueOf(r.json())).isEqualTo(201);
        return r.json();
    }

    public JsonNode tenant(String id, int limit) {
        return tenant(id, "TOKEN_BUCKET", limit, 60);
    }

    public JsonNode tenant() {
        return tenant("acme", 5);
    }

    public static String key(JsonNode created) {
        return created.get("apiKey").asString();
    }

    public Res check(String apiKey, Object body) {
        return call("POST", "/api/v1/limits/check", body, Map.of("X-API-Key", apiKey));
    }

    public Res check(String apiKey) {
        return check(apiKey, null);
    }

    public Res hello(String apiKey) {
        return call("GET", "/api/v1/demo/hello", null, Map.of("X-API-Key", apiKey));
    }
}
