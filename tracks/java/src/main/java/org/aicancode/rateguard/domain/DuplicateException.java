package org.aicancode.rateguard.domain;

/** Thrown by a store when a tenant id is already taken (SADD said 0: Redis decided the race). */
public class DuplicateException extends RuntimeException {

    public DuplicateException(String id) {
        super(id);
    }
}
