package org.aicancode.rateguard.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** Domain errors that map 1:1 to RFC 9457 problem responses (identical in every track). */
public class ApiException extends RuntimeException {

    public static final String PROBLEM_BASE = "https://rateguard.example/problems/";

    private final int status;
    private final String slug;
    private final String title;
    private final String detail;
    private final Map<String, String> headers;

    public ApiException(int status, String slug, String title, String detail, Map<String, String> headers) {
        super(detail == null ? title : detail);
        this.status = status;
        this.slug = slug;
        this.title = title;
        this.detail = detail;
        this.headers = headers;
    }

    public ApiException(int status, String slug, String title, String detail) {
        this(status, slug, title, detail, Map.of());
    }

    public static ApiException validation(String detail) {
        return new ApiException(400, "validation-error", "Validation failed", detail);
    }

    public static ApiException unauthorized() {
        return new ApiException(401, "unauthorized", "Unauthorized", "admin token required",
                Map.of("WWW-Authenticate", "Bearer"));
    }

    public static ApiException invalidApiKey() {
        return new ApiException(401, "invalid-api-key", "Invalid API key", "a valid X-API-Key header is required");
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, "not-found", "Not found", what + " not found");
    }

    public static ApiException tenantExists() {
        return new ApiException(409, "tenant-exists", "Tenant already exists", "a tenant with this id exists");
    }

    public static ApiException costExceedsLimit(int cost, int limit) {
        return new ApiException(422, "cost-exceeds-limit", "Cost exceeds limit",
                "cost " + cost + " is more than the limit of " + limit);
    }

    public static ApiException rateLimited(int limit, int windowSeconds, long retryAfterS, Map<String, String> headers) {
        return new ApiException(429, "rate-limited", "Too many requests",
                "limit of " + limit + " per " + windowSeconds + " s reached, retry in " + retryAfterS + " s", headers);
    }

    public static ApiException limiterUnavailable() {
        return new ApiException(503, "limiter-unavailable", "Limiter unavailable", "the rate-limit store is unavailable",
                Map.of("Retry-After", "1"));
    }

    public int status() {
        return status;
    }

    public String slug() {
        return slug;
    }

    public String detail() {
        return detail;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public Map<String, Object> toProblem() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", PROBLEM_BASE + slug);
        body.put("title", title);
        body.put("status", status);
        if (detail != null) {
            body.put("detail", detail);
        }
        return body;
    }
}
