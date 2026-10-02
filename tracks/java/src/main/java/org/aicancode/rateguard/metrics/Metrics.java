package org.aicancode.rateguard.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v1.1: hand-rolled Prometheus counters (text exposition format 0.0.4).
 * No client library on purpose: /metrics is just text. Names are identical in all three tracks.
 */
public class Metrics {

    public static final String DECISIONS_ALLOWED = "rateguard_decisions_allowed_total";
    public static final String DECISIONS_DENIED = "rateguard_decisions_denied_total";
    public static final String DEGRADED_DECISIONS = "rateguard_degraded_decisions_total";
    public static final String STORE_ERRORS = "rateguard_store_errors_total";
    public static final String STORE_SHORT_CIRCUITS = "rateguard_store_short_circuits_total";
    public static final String TENANTS_CREATED = "rateguard_tenants_created_total";
    public static final String AI_FALLBACK = "rateguard_ai_fallback_total";
    public static final String LLM_SHORT_CIRCUITS = "rateguard_llm_short_circuits_total";

    public static final Map<String, String> HELP = new LinkedHashMap<>();

    static {
        HELP.put(DECISIONS_ALLOWED, "Limiter checks that were allowed.");
        HELP.put(DECISIONS_DENIED, "Limiter checks that were denied.");
        HELP.put(DEGRADED_DECISIONS, "Checks decided by the tenant's failMode because Redis was unavailable.");
        HELP.put(STORE_ERRORS, "Redis calls that failed (refused, timed out, broken connection).");
        HELP.put(STORE_SHORT_CIRCUITS, "Redis calls skipped because the circuit breaker was open.");
        HELP.put(TENANTS_CREATED, "Tenants created with POST /admin/tenants.");
        HELP.put(AI_FALLBACK, "Advice requests that fell back to the offline rules.");
        HELP.put(LLM_SHORT_CIRCUITS, "LLM calls skipped because the circuit breaker was open.");
    }

    private final Map<String, AtomicLong> values = new LinkedHashMap<>();

    public Metrics() {
        HELP.keySet().forEach(name -> values.put(name, new AtomicLong()));
    }

    public void inc(String name) {
        inc(name, 1);
    }

    public void inc(String name, long amount) {
        AtomicLong counter = values.get(name);
        if (counter == null) {
            throw new IllegalArgumentException("unknown metric " + name);
        }
        counter.addAndGet(amount);
    }

    public long value(String name) {
        return values.get(name).get();
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        HELP.forEach((name, help) -> sb.append("# HELP ").append(name).append(' ').append(help).append('\n')
                .append("# TYPE ").append(name).append(" counter\n")
                .append(name).append(' ').append(values.get(name).get()).append('\n'));
        return sb.toString();
    }
}
