package org.aicancode.rateguard.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aicancode.rateguard.domain.Models.Policy;
import org.aicancode.rateguard.domain.Models.UsagePoint;
import org.aicancode.rateguard.domain.Times;

/**
 * M5: the facts the advisor works from, always computed by code (never by the LLM). Integers only, identical in
 * every track.
 *
 * @param deniedPercent denied * 100 / total, rounded down
 * @param peak most requests in one minute
 * @param peakMinute epoch minute of the (first) peak, null without traffic
 * @param peakDenied denials in that minute
 * @param median lower median of the minutes that had traffic
 * @param p90 90th percentile (nearest rank) of the minutes that had traffic
 * @param capacityPerMinute limit * 60 / windowSeconds, rounded down
 */
public record UsageStats(int minutes, long allowed, long denied, long deniedPercent, long peak, Long peakMinute,
        long peakDenied, long median, long p90, int activeMinutes, long capacityPerMinute) {

    public long total() {
        return allowed + denied;
    }

    public static UsageStats summarize(Policy policy, List<UsagePoint> points) {
        long allowed = 0;
        long denied = 0;
        long peak = 0;
        int peakIndex = -1;
        List<Long> active = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            UsagePoint p = points.get(i);
            long demand = p.allowed() + p.denied();
            allowed += p.allowed();
            denied += p.denied();
            if (demand > peak) {
                peak = demand;
                peakIndex = i;
            }
            if (demand > 0) {
                active.add(demand);
            }
        }
        active.sort(null);
        int n = active.size();
        long total = allowed + denied;
        return new UsageStats(points.size(), allowed, denied, total == 0 ? 0 : denied * 100 / total, peak,
                peakIndex >= 0 ? points.get(peakIndex).minute() : null,
                peakIndex >= 0 ? points.get(peakIndex).denied() : 0,
                n == 0 ? 0 : active.get((n - 1) / 2),
                n == 0 ? 0 : active.get((9 * n + 9) / 10 - 1),
                n, (long) policy.limit() * 60 / policy.windowSeconds());
    }

    /** The API shape (also what the LLM sees). */
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("minutes", minutes);
        m.put("allowed", allowed);
        m.put("denied", denied);
        m.put("deniedPercent", deniedPercent);
        m.put("peakPerMinute", peak);
        m.put("peakMinute", peakMinute == null ? null : Times.isoMs(peakMinute * 60_000));
        m.put("peakMinuteDenied", peakDenied);
        m.put("medianPerMinute", median);
        m.put("p90PerMinute", p90);
        m.put("activeMinutes", activeMinutes);
        m.put("capacityPerMinute", capacityPerMinute);
        return m;
    }
}
