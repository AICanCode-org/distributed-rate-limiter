package org.aicancode.rateguard.security;

import org.aicancode.rateguard.domain.ApiException;

/** The admin API is protected by one shared bearer token (ADMIN_TOKEN). */
public class AdminAuth {

    private final String token;

    public AdminAuth(String token) {
        this.token = token;
    }

    public void require(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw ApiException.unauthorized();
        }
        if (!ApiKeys.constantTimeEquals(authorization.substring("Bearer ".length()).strip(), token)) {
            throw ApiException.unauthorized();
        }
    }
}
