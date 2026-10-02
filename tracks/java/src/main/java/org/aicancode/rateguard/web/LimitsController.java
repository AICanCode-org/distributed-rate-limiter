package org.aicancode.rateguard.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.aicancode.rateguard.domain.Models.Tenant;
import org.aicancode.rateguard.domain.Models.Verdict;
import org.aicancode.rateguard.service.LimiterService;
import org.aicancode.rateguard.service.TenantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** The tenant side: the decision API (always 200) and a demo route protected by the limiter (429 when denied). */
@RestController
public class LimitsController {

    private static final String API_KEY = "X-API-Key";

    private final TenantService tenants;
    private final LimiterService limiter;

    public LimitsController(TenantService tenants, LimiterService limiter) {
        this.tenants = tenants;
        this.limiter = limiter;
    }

    private static ResponseEntity.BodyBuilder ok(Verdict v) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        LimiterService.headers(v).forEach(response::header);
        return response;
    }

    @PostMapping("/api/v1/limits/check")
    public ResponseEntity<Map<String, Object>> check(@RequestHeader(value = API_KEY, required = false) String apiKey,
            @RequestBody(required = false) JsonNode body) {
        Verdict v = limiter.check(tenants.authenticate(apiKey), body);
        return ok(v).body(Json.verdict(v));
    }

    @GetMapping("/api/v1/demo/hello")
    public ResponseEntity<Map<String, Object>> hello(@RequestHeader(value = API_KEY, required = false) String apiKey) {
        Tenant tenant = tenants.authenticate(apiKey);
        Verdict v = limiter.enforce(tenant);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Hello, " + tenant.name() + "!");
        body.put("tenantId", tenant.id());
        return ok(v).body(body);
    }
}
