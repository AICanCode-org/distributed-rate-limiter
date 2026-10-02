package org.aicancode.rateguard.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aicancode.rateguard.ai.Advice;
import org.aicancode.rateguard.ai.LlmAdvisor;
import org.aicancode.rateguard.ai.UsageStats;
import org.aicancode.rateguard.domain.Models.Policy;
import org.aicancode.rateguard.domain.Models.Tenant;
import org.aicancode.rateguard.domain.Models.UsagePoint;
import org.aicancode.rateguard.domain.Models.Verdict;
import org.aicancode.rateguard.domain.Times;
import org.aicancode.rateguard.service.TenantService.Usage;

/** JSON shapes (identical in every track, see spec/openapi.yaml). LinkedHashMap keeps the documented field order. */
final class Json {

    private Json() {
    }

    static Map<String, Object> policy(Policy p) {
        Map<String, Object> m = LlmAdvisor.policyJson(p);
        m.put("failMode", p.failMode().name());
        return m;
    }

    static Map<String, Object> tenant(Tenant t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("name", t.name());
        m.put("policy", policy(t.policy()));
        m.put("keyPrefix", t.keyPrefix());
        m.put("createdAt", Times.iso(t.createdAt()));
        m.put("updatedAt", Times.iso(t.updatedAt()));
        return m;
    }

    static Map<String, Object> tenantWithKey(Tenant t, String apiKey) {
        Map<String, Object> m = tenant(t);
        m.put("apiKey", apiKey);
        return m;
    }

    static Map<String, Object> tenants(List<Tenant> tenants) {
        return Map.of("items", tenants.stream().map(Json::tenant).toList());
    }

    static Map<String, Object> verdict(Verdict v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("allowed", v.decision().allowed());
        m.put("tenantId", v.tenant().id());
        m.put("resource", v.resource());
        m.put("cost", v.cost());
        m.put("algorithm", v.tenant().policy().algorithm().name());
        m.put("limit", v.tenant().policy().limit());
        m.put("remaining", v.decision().remaining());
        m.put("resetAfterMs", v.decision().resetAfterMs());
        m.put("retryAfterMs", v.decision().retryAfterMs());
        m.put("degraded", v.decision().degraded());
        return m;
    }

    static Map<String, Object> usage(Usage u) {
        long allowed = 0;
        long denied = 0;
        List<Map<String, Object>> series = new ArrayList<>();
        for (UsagePoint p : u.points()) {
            allowed += p.allowed();
            denied += p.denied();
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("minute", Times.isoMs(p.minute() * 60_000));
            point.put("allowed", p.allowed());
            point.put("denied", p.denied());
            series.add(point);
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("allowed", allowed);
        totals.put("denied", denied);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", u.tenant().id());
        m.put("minutes", u.minutes());
        m.put("totals", totals);
        m.put("series", series);
        return m;
    }

    static Map<String, Object> advice(Tenant t, UsageStats stats, Advice.Result r, String generatedAt) {
        Advice a = r.advice();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", t.id());
        m.put("provider", r.provider());
        m.put("fallback", r.fallback());
        m.put("currentPolicy", policy(t.policy()));
        m.put("stats", stats.toJson());
        m.put("action", a.action().name());
        m.put("suggestedPolicy", a.limit() == null ? null : policy(t.policy().withLimit(a.limit())));
        m.put("summary", a.summary());
        m.put("generatedAt", generatedAt);
        return m;
    }

    static Map<String, Object> health(boolean redisUp) {
        Map<String, Object> m = new LinkedHashMap<>();
        // v1.1: without Redis the app still answers (from each tenant's failMode): DEGRADED, not down.
        m.put("status", redisUp ? "UP" : "DEGRADED");
        m.put("checks", Map.of("redis", redisUp ? "UP" : "DOWN"));
        return m;
    }
}
