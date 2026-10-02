package org.aicancode.rateguard.security;


/** The admin API is protected by one shared bearer token (ADMIN_TOKEN). */
public class AdminAuth {

    private final String token;

    public AdminAuth(String token) {
        this.token = token;
    }

    public void require(String authorization) {
        // TODO(phase-3 M1): the Authorization header must be 'Bearer <token>' and the token must equal
        //   ADMIN_TOKEN (constant time); anything else -> 401 unauthorized (the HTTP layer adds WWW-
        //   Authenticate: Bearer).
        throw new UnsupportedOperationException("TODO(phase-3 M1): AdminAuth.require");
    }
}
