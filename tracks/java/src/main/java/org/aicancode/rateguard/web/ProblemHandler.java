package org.aicancode.rateguard.web;

import java.util.Map;
import org.aicancode.rateguard.domain.ApiException;
import org.aicancode.rateguard.domain.StoreUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps exceptions to application/problem+json, with the headers the problem carries (WWW-Authenticate, Retry-After). */
@RestControllerAdvice
public class ProblemHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        return problem(e);
    }

    @ExceptionHandler(StoreUnavailableException.class)
    public ResponseEntity<Map<String, Object>> storeDown(StoreUnavailableException e) {
        LOG.warn("store unavailable: {}", e.getMessage());
        return problem(ApiException.limiterUnavailable());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<Map<String, Object>> unreadable(Exception e) {
        return problem(ApiException.validation("request body must be a JSON object"));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> noRoute(Exception e) {
        return problem(ApiException.notFound("route"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> wrongMethod(HttpRequestMethodNotSupportedException e) {
        return problem(new ApiException(405, "http-error", "Method Not Allowed", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        LOG.error("unhandled error", e);
        return problem(new ApiException(500, "internal-error", "Internal server error", null));
    }

    static ResponseEntity<Map<String, Object>> problem(ApiException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        e.headers().forEach(response::header);
        return response.body(e.toProblem());
    }
}
