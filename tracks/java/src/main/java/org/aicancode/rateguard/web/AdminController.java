package org.aicancode.rateguard.web;

import java.time.Clock;
import java.util.Map;
import org.aicancode.rateguard.ai.AdvisorService;
import org.aicancode.rateguard.ai.UsageStats;
import org.aicancode.rateguard.domain.Times;
import org.aicancode.rateguard.security.AdminAuth;
import org.aicancode.rateguard.service.TenantService;
import org.aicancode.rateguard.service.TenantService.Created;
import org.aicancode.rateguard.service.TenantService.Usage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** The admin API: tenants, policies, keys, usage and advice. Every route needs the admin bearer token. */
@RestController
@RequestMapping("/api/v1/admin/tenants")
public class AdminController {

    private static final String AUTH = "Authorization";

    private final AdminAuth admin;
    private final TenantService tenants;
    private final AdvisorService advisor;
    private final Clock clock;

    public AdminController(AdminAuth admin, TenantService tenants, AdvisorService advisor, Clock clock) {
        this.admin = admin;
        this.tenants = tenants;
        this.advisor = advisor;
        this.clock = clock;
    }

    private TenantService asAdmin(String authorization) {
        admin.require(authorization);
        return tenants;
    }

    /** A repeated query parameter (?minutes=1&minutes=2) counts as its last value, like in the other tracks. */
    private static String last(String[] values) {
        return values == null || values.length == 0 ? null : values[values.length - 1];
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestHeader(value = AUTH, required = false) String auth,
            @RequestBody(required = false) JsonNode body) {
        Created c = asAdmin(auth).create(body);
        return ResponseEntity.status(201).header("Location", "/api/v1/admin/tenants/" + c.tenant().id())
                .body(Json.tenantWithKey(c.tenant(), c.apiKey()));
    }

    @GetMapping
    public Map<String, Object> list(@RequestHeader(value = AUTH, required = false) String auth) {
        return Json.tenants(asAdmin(auth).list());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable String id) {
        return Json.tenant(asAdmin(auth).get(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@RequestHeader(value = AUTH, required = false) String auth,
            @PathVariable String id) {
        asAdmin(auth).delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/policy")
    public Map<String, Object> policy(@RequestHeader(value = AUTH, required = false) String auth,
            @PathVariable String id, @RequestBody(required = false) JsonNode body) {
        return Json.tenant(asAdmin(auth).setPolicy(id, body));
    }

    @PostMapping("/{id}/rotate-key")
    public Map<String, Object> rotateKey(@RequestHeader(value = AUTH, required = false) String auth,
            @PathVariable String id) {
        Created c = asAdmin(auth).rotateKey(id);
        return Json.tenantWithKey(c.tenant(), c.apiKey());
    }

    @PostMapping("/{id}/reset")
    public ResponseEntity<Void> reset(@RequestHeader(value = AUTH, required = false) String auth,
            @PathVariable String id) {
        asAdmin(auth).reset(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/usage")
    public Map<String, Object> usage(@RequestHeader(value = AUTH, required = false) String auth,
            @PathVariable String id, @RequestParam(value = "minutes", required = false) String[] minutes) {
        return Json.usage(asAdmin(auth).usage(id, last(minutes)));
    }

    @PostMapping("/{id}/advice")
    public Map<String, Object> advice(@RequestHeader(value = AUTH, required = false) String auth,
            @PathVariable String id, @RequestParam(value = "minutes", required = false) String[] minutes) {
        Usage u = asAdmin(auth).usage(id, last(minutes));
        UsageStats stats = UsageStats.summarize(u.tenant().policy(), u.points());
        return Json.advice(u.tenant(), stats, advisor.advise(u.tenant(), stats), Times.iso(Times.now(clock)));
    }
}
