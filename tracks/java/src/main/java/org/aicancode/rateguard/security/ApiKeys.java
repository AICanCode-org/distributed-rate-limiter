package org.aicancode.rateguard.security;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** M1: API keys and secrets. Keys are shown once and stored only as a SHA-256 hash. */
public final class ApiKeys {

    public static final String PREFIX = "rg_";
    public static final Pattern API_KEY = Pattern.compile("^rg_[0-9a-f]{32}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger LOG = LoggerFactory.getLogger(ApiKeys.class);

    private ApiKeys() {
    }

    /** 'rg_' + 32 lowercase hex characters (128 random bits from a CSPRNG). */
    public static String newApiKey() {
        // TODO(phase-3 M1): 'rg_' + 32 lowercase hex characters from a CSPRNG (128 random bits). Never
        //   Math.random / random.random.
        throw new UnsupportedOperationException("TODO(phase-3 M1): ApiKeys.newApiKey");
    }

    /** Hex SHA-256. A fast hash is fine here: the key is 128 random bits, not a guessable password. */
    public static String hash(String apiKey) {
        // TODO(phase-3 M1): hex SHA-256 of the key (UTF-8). Only this hash is stored; a fast hash is fine for
        //   128 random bits.
        throw new UnsupportedOperationException("TODO(phase-3 M1): ApiKeys.hash");
    }

    /** The first 9 characters ('rg_' + 6 hex): enough to recognise a key in a list, useless to an attacker. */
    public static String prefix(String apiKey) {
        // TODO(phase-3 M1): the first 9 characters ('rg_' + 6 hex): shown in lists so humans can tell keys
        //   apart.
        throw new UnsupportedOperationException("TODO(phase-3 M1): ApiKeys.prefix");
    }

    public static boolean looksLikeApiKey(String value) {
        // TODO(phase-3 M1): true only for 'rg_' + exactly 32 lowercase hex characters (malformed keys never
        //   reach Redis).
        throw new UnsupportedOperationException("TODO(phase-3 M1): ApiKeys.looksLikeApiKey");
    }

    /** Compare secrets without leaking, through timing, how many leading characters match. */
    public static boolean constantTimeEquals(String a, String b) {
        // TODO(phase-3 M1): compare two secrets in constant time (hmac.compare_digest /
        //   crypto.timingSafeEqual on equal-length buffers / MessageDigest.isEqual), never ==.
        throw new UnsupportedOperationException("TODO(phase-3 M1): ApiKeys.constantTimeEquals");
    }

    /** Empty means "not set": a random per-process value is used (dev only), with a warning. */
    public static String resolveSecret(String configured, String name) {
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        LOG.warn("{} is not set: using a random value for this process (dev only)", name);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
