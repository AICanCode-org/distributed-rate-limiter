package org.aicancode.rateguard.ai;

/**
 * What the advisor suggests. {@code limit} is the suggested limit for RAISE_LIMIT / LOWER_LIMIT, else null.
 */
public record Advice(Action action, Integer limit, String summary) {

    public static final int SUMMARY_MAX = 400;

    public enum Action { KEEP, RAISE_LIMIT, LOWER_LIMIT, INVESTIGATE }

    /** Which advisor answered, and whether the LLM failed so the rules answered instead. */
    public record Result(String provider, boolean fallback, Advice advice) {
    }
}
