package org.aicancode.rateguard.domain;

import java.util.regex.Pattern;
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
        // TODO(phase-3 M1): a string (400 'id is required') matching TENANT_ID_RE: 3-32 characters of a-z,
        //   0-9 and inner hyphens (400 'id must be 3-32 characters of a-z, 0-9 and inner hyphens'). Ids end up
        //   inside Redis keys, so ':', '{' and '*' must never get through.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.tenantId");
    }

    public static boolean isTenantId(String value) {
        // TODO(phase-3 M1): true when the string matches TENANT_ID_RE (used to answer 404 for impossible ids
        //   without asking Redis).
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.isTenantId");
    }

    public static String tenantName(JsonNode node) {
        // TODO(phase-3 M1): a string (400 'name is required'), trimmed, 1-80 characters counted as code
        //   points (400 'name must be 1-80 characters').
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.tenantName");
    }

    public static Policy policy(JsonNode node) {
        // TODO(phase-3 M1): an object (400 'policy is required'); algorithm TOKEN_BUCKET or SLIDING_WINDOW;
        //   limit an INTEGER 1..MAX_LIMIT (booleans and floats are not integers); windowSeconds an integer
        //   1..MAX_WINDOW_SECONDS. Messages: see the validation tests.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.policy");
    }

    public static String resource(JsonNode node) {
        // TODO(phase-3 M1): missing/null -> DEFAULT_RESOURCE ('default'); otherwise a string matching
        //   RESOURCE_RE (1-64 of a-z 0-9 . _ : -), else 400.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.resource");
    }

    public static int cost(JsonNode node) {
        // TODO(phase-3 M1): missing/null -> 1; otherwise an integer 1..MAX_COST (1000), else 400 'cost must
        //   be an integer between 1 and 1000'.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.cost");
    }

    /** A query string: missing -> 60; ASCII digits only, 1..120. */
    public static int minutes(String value) {
        // TODO(phase-3 M1): a query string: missing -> DEFAULT_MINUTES (60); ASCII digits only and
        //   1..MAX_MINUTES (120), else 400 'minutes must be an integer between 1 and 120'.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.minutes");
    }
}
