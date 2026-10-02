package org.aicancode.rateguard.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * v1.1 (Phase 7): one request ID per request. It is echoed in the X-Request-ID response header, put in the
 * logging MDC (so every log line carries it) and written in one access-log line per request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-ID";
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Logger ACCESS = LoggerFactory.getLogger("rateguard.access");

    /** Reuse a caller's ID only if it is safe to echo into headers/logs; otherwise mint one. */
    public static String incomingOrNewId(String value) {
        return value != null && SAFE_ID.matcher(value).matches() ? value : UUID.randomUUID().toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = incomingOrNewId(request.getHeader(HEADER));
        long start = System.nanoTime();
        MDC.put("requestId", requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            ACCESS.atInfo().setMessage("request")
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("path", request.getRequestURI())
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000.0)
                    .log();
            MDC.remove("requestId");
        }
    }
}
