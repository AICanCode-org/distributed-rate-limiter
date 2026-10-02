package org.aicancode.rateguard.support;

import java.util.Map;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * PENDING test classes describe behaviour you have not built yet, so they are skipped
 * (Maven reports them as "Skipped"). When you start a milestone, delete its line from
 * {@link #PENDING}, watch the tests fail (red), implement until they pass (green).
 * Registered for every test automatically (see src/test/resources/junit-platform.properties).
 */
public class Pending implements ExecutionCondition {

    /** Test class simple name -> milestone. */
    static final Map<String, String> PENDING = Map.ofEntries(
            Map.entry("ValidationTest", "Phase 3 M1 - validation"),
            Map.entry("SecurityTest", "Phase 3 M1 - API keys and the admin token"),
            Map.entry("TokenBucketTest", "Phase 3 M2 - token bucket"),
            Map.entry("SlidingWindowTest", "Phase 3 M2 - sliding window counter"),
            Map.entry("InMemoryStoreTest", "Phase 3 M2 - the in-memory store on your algorithms"),
            Map.entry("KeysTest", "Phase 3 M3 - Redis key layout"),
            Map.entry("TenantServiceTest", "Phase 3 M4 - TenantService"),
            Map.entry("LimiterServiceTest", "Phase 3 M4 - LimiterService"),
            Map.entry("ApiAdminTest", "Phase 3 M4 - admin endpoints"),
            Map.entry("ApiLimitsTest", "Phase 3 M4 - limiter endpoints"),
            Map.entry("AdvisorTest", "Phase 3 M5 - usage stats and the rule advisor"),
            Map.entry("ApiAdviceTest", "Phase 3 M5 - advice endpoint"),
            Map.entry("RedisStoreIntegrationTest", "Phase 4 - integration tests"),
            Map.entry("ApiIntegrationTest", "Phase 4 - integration tests"));

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return context.getTestClass()
                .map(c -> PENDING.get(c.getSimpleName()))
                .map(reason -> ConditionEvaluationResult.disabled("PENDING (" + reason + ") - see support/Pending.java"))
                .orElse(ConditionEvaluationResult.enabled("not pending"));
    }
}
