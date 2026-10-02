package org.aicancode.rateguard.ai;

import java.util.List;
import java.util.Map;
import org.aicancode.rateguard.domain.Models.Policy;
import org.aicancode.rateguard.domain.Models.UsagePoint;

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
        // TODO(phase-3 M5): the numbers the advice is based on, computed by CODE (never by the LLM): totals,
        //   deniedPercent (integer, rounded down), peak minute (first one wins) and its denials, median and p90
        //   of the ACTIVE minutes, capacity per minute = limit * 60 // windowSeconds. See the advisor tests for
        //   the exact definitions.
        throw new UnsupportedOperationException("TODO(phase-3 M5): UsageStats.summarize");
    }

    /** The API shape (also what the LLM sees). */
    public Map<String, Object> toJson() {
        // TODO(phase-3 M5): the stats as the JSON object of the API (camelCase names, peakMinute as an ISO
        //   time or null).
        throw new UnsupportedOperationException("TODO(phase-3 M5): UsageStats.toJson");
    }
}
