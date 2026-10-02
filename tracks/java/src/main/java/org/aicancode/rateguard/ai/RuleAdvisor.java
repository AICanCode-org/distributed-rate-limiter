package org.aicancode.rateguard.ai;

import org.aicancode.rateguard.ai.Advice.Action;
import org.aicancode.rateguard.domain.Models.Policy;
import org.aicancode.rateguard.domain.Times;
import org.aicancode.rateguard.domain.Validation;

/** Five rules, checked in order. Deterministic: the same numbers always give the same advice (the offline default). */
public class RuleAdvisor {

    private static String hhmm(long minute) {
        return Times.isoMs(minute * 60_000).substring(11, 16);
    }

    public Advice advise(Policy policy, UsageStats s) {
        long limit = policy.limit();
        long w = policy.windowSeconds();
        if (s.total() == 0) {
            return new Advice(Action.KEEP, null,
                    "No requests in the last " + s.minutes() + " minutes: keep the current policy.");
        }
        if (s.denied() > 0 && s.activeMinutes() >= 3 && s.peak() >= 5 * Math.max(1, s.median())
                && 2 * s.peakDenied() >= s.denied()) {
            long ratio = s.peak() / Math.max(1, s.median());
            return new Advice(Action.INVESTIGATE, null, "Spike at " + hhmm(s.peakMinute() == null ? 0 : s.peakMinute())
                    + " UTC: " + s.peak() + " requests in one minute, " + ratio + "x the usual " + s.median() + ". "
                    + s.peakDenied() + " of the " + s.denied() + " denials happened in that minute, which looks like "
                    + "a retry storm or abuse, not growth. Keep the limit and check the client.");
        }
        if (s.deniedPercent() >= 10) {
            long wanted = (s.p90() * w * 12 + 599) / 600; // p90 per minute, scaled to the window, + 20 % headroom
            long next = Math.min(Validation.MAX_LIMIT, Math.min(limit * 10, Math.max(limit + 1, wanted)));
            if (next > limit) {
                return new Advice(Action.RAISE_LIMIT, (int) next, s.deniedPercent() + "% of requests were denied ("
                        + s.denied() + " of " + s.total() + "). Steady demand is about " + s.p90()
                        + " per minute (90th percentile) against a capacity of " + s.capacityPerMinute()
                        + " per minute. Raise the limit to " + next + " per " + w + " s (20% headroom).");
            }
        }
        if (s.activeMinutes() >= 10 && limit >= 2 && s.peak() * 4 * w <= limit * 60) {
            long next = Math.max(1, (s.peak() * w * 2 + 59) / 60);
            return new Advice(Action.LOWER_LIMIT, (int) next, "Peak demand was " + s.peak()
                    + " per minute, under a quarter of the capacity of " + s.capacityPerMinute() + " per minute. "
                    + next + " per " + w + " s (twice the peak) still fits and limits the damage if the key leaks.");
        }
        return new Advice(Action.KEEP, null, s.deniedPercent() + "% denied and a peak of " + s.peak()
                + " per minute against a capacity of " + s.capacityPerMinute() + " per minute: the current policy fits.");
    }
}
