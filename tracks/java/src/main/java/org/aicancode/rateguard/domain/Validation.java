package org.aicancode.rateguard.domain;

import java.util.regex.Pattern;
import org.aicancode.rateguard.domain.Models.Algorithm;
import org.aicancode.rateguard.domain.Models.FailMode;
import org.aicancode.rateguard.domain.Models.Policy;
import tools.jackson.databind.JsonNode;

/** M1: input validation. Every message is identical in all three tracks (clients and tests rely on them). */
public final class Validation {

    public static final Pattern TENANT_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$");
    public static final Pattern RESOURCE = Pattern.compile("^[a-z0-9._:-]{1,64}$");
    public static final Pattern DIGITS = Pattern.compile("^[0-9]+$");
    public static final int MAX_LIMIT = 100_000;
    public static final int MAX_WINDOW_SECONDS = 86_400;
    public static final int MAX_COST = 1_000;
    public static final int MAX_NAME = 80;
    public static final String DEFAULT_RESOURCE = "default";
    public static final int DEFAULT_MINUTES = 60;
    public static final int MAX_MINUTES = 120;

    private Validation() {
    }

    public static JsonNode requireObject(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ApiException.validation("request body must be a JSON object");
        }
        return body;
    }

    private static boolean absent(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    /** A JSON integer (1.5, "1" and true are not), returned as a long so huge values are still range-checked. */
    private static Long integer(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong() ? node.asLong() : null;
    }

    /** Tenant ids end up inside Redis keys, so only a small, safe alphabet is accepted (no ':', '{', '*'). */
    public static String tenantId(JsonNode node) {
        if (node == null || !node.isString()) {
            throw ApiException.validation("id is required");
        }
        String id = node.asString();
        if (!TENANT_ID.matcher(id).matches()) {
            throw ApiException.validation("id must be 3-32 characters of a-z, 0-9 and inner hyphens");
        }
        return id;
    }

    public static boolean isTenantId(String value) {
        return value != null && TENANT_ID.matcher(value).matches();
    }

    public static String tenantName(JsonNode node) {
        if (node == null || !node.isString()) {
            throw ApiException.validation("name is required");
        }
        String name = node.asString().strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > MAX_NAME) {
            throw ApiException.validation("name must be 1-80 characters");
        }
        return name;
    }

    public static Policy policy(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw ApiException.validation("policy is required");
        }
        JsonNode algorithm = node.get("algorithm");
        Algorithm parsed = null;
        if (algorithm != null && algorithm.isString()) {
            for (Algorithm a : Algorithm.values()) {
                if (a.name().equals(algorithm.asString())) {
                    parsed = a;
                }
            }
        }
        if (parsed == null) {
            throw ApiException.validation("policy.algorithm must be TOKEN_BUCKET or SLIDING_WINDOW");
        }
        Long limit = integer(node.get("limit"));
        if (limit == null || limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.validation("policy.limit must be an integer between 1 and 100000");
        }
        Long window = integer(node.get("windowSeconds"));
        if (window == null || window < 1 || window > MAX_WINDOW_SECONDS) {
            throw ApiException.validation("policy.windowSeconds must be an integer between 1 and 86400");
        }
        // v1.1: optional, so v1.0 clients keep working (they get CLOSED, the v1.0 behaviour)
        JsonNode failMode = node.get("failMode");
        FailMode mode = failMode == null ? FailMode.CLOSED : null;
        if (failMode != null && failMode.isString()) {
            for (FailMode m : FailMode.values()) {
                if (m.name().equals(failMode.asString())) {
                    mode = m;
                }
            }
        }
        if (mode == null) {
            throw ApiException.validation("policy.failMode must be CLOSED, OPEN or LOCAL");
        }
        return new Policy(parsed, limit.intValue(), window.intValue(), mode);
    }

    public static String resource(JsonNode node) {
        if (absent(node)) {
            return DEFAULT_RESOURCE;
        }
        if (!node.isString() || !RESOURCE.matcher(node.asString()).matches()) {
            throw ApiException.validation("resource must be 1-64 characters of a-z, 0-9, '.', '_', ':' and '-'");
        }
        return node.asString();
    }

    public static int cost(JsonNode node) {
        if (absent(node)) {
            return 1;
        }
        Long cost = integer(node);
        if (cost == null || cost < 1 || cost > MAX_COST) {
            throw ApiException.validation("cost must be an integer between 1 and 1000");
        }
        return cost.intValue();
    }

    /** A query string: missing -> 60; ASCII digits only, 1..120. */
    public static int minutes(String value) {
        if (value == null) {
            return DEFAULT_MINUTES;
        }
        if (!DIGITS.matcher(value).matches() || value.length() > 9 || Integer.parseInt(value) < 1
                || Integer.parseInt(value) > MAX_MINUTES) {
            throw ApiException.validation("minutes must be an integer between 1 and 120");
        }
        return Integer.parseInt(value);
    }
}
