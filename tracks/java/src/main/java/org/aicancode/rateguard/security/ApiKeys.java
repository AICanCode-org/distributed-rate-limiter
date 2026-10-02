package org.aicancode.rateguard.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
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
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return PREFIX + HexFormat.of().formatHex(bytes);
    }

    /** Hex SHA-256. A fast hash is fine here: the key is 128 random bits, not a guessable password. */
    public static String hash(String apiKey) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The first 9 characters ('rg_' + 6 hex): enough to recognise a key in a list, useless to an attacker. */
    public static String prefix(String apiKey) {
        return apiKey.substring(0, 9);
    }

    public static boolean looksLikeApiKey(String value) {
        return value != null && API_KEY.matcher(value).matches();
    }

    /** Compare secrets without leaking, through timing, how many leading characters match. */
    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
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
