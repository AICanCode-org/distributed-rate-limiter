package org.aicancode.rateguard.ai;

import org.aicancode.rateguard.domain.Models.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M5: the AI policy advisor, with a deterministic offline fallback.
 *
 * <p>An admin asks "is this tenant's policy right?". The statistics are always computed by code. Then either the rule
 * advisor (default, offline) or an LLM explains them and suggests an action. ANY LLM failure (timeout, HTTP error,
 * an answer that does not validate) means: use the rules, with fallback=true. A suggestion is never applied
 * automatically: the admin reads it and decides with PUT /policy (human in the loop).
 */
public class AdvisorService {

    private static final Logger LOG = LoggerFactory.getLogger(AdvisorService.class);

    private final RuleAdvisor rules;
    private final LlmAdvisor llm;
    private final Runnable onFallback;

    public AdvisorService(RuleAdvisor rules, LlmAdvisor llm, Runnable onFallback) {
        this.rules = rules;
        this.llm = llm;
        this.onFallback = onFallback;
    }

    public AdvisorService(RuleAdvisor rules, LlmAdvisor llm) {
        this(rules, llm, () -> { });
    }

    public Advice.Result advise(Tenant tenant, UsageStats stats) {
        if (llm != null) {
            try {
                return new Advice.Result("llm", false, llm.advise(tenant, stats));
            } catch (Exception e) {
                LOG.warn("LLM advice failed, using the rules: {}", e.getClass().getSimpleName());
                onFallback.run();
                return new Advice.Result("rules", true, rules.advise(tenant.policy(), stats));
            }
        }
        return new Advice.Result("rules", false, rules.advise(tenant.policy(), stats));
    }
}
