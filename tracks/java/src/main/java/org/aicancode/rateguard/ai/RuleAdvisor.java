package org.aicancode.rateguard.ai;

import org.aicancode.rateguard.domain.Models.Policy;
import org.aicancode.rateguard.domain.Times;

/** Five rules, checked in order. Deterministic: the same numbers always give the same advice (the offline default). */
public class RuleAdvisor {

    private static String hhmm(long minute) {
        return Times.isoMs(minute * 60_000).substring(11, 16);
    }

    public Advice advise(Policy policy, UsageStats s) {
        // TODO(phase-3 M5): five rules in order: no traffic -> KEEP; a spike (>= 3 active minutes, peak >= 5x
        //   median, at least half the denials in the peak minute) -> INVESTIGATE; >= 10 % denied -> RAISE_LIMIT
        //   to the p90 per minute scaled to the window + 20 % (at most 10x and MAX_LIMIT); >= 10 active minutes
        //   and peak under a quarter of capacity -> LOWER_LIMIT to twice the peak; else KEEP. The exact summary
        //   texts are in the tests.
        throw new UnsupportedOperationException("TODO(phase-3 M5): RuleAdvisor.advise");
    }
}
