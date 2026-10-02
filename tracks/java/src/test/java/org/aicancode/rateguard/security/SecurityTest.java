package org.aicancode.rateguard.security;

import static org.aicancode.rateguard.support.Problems.statusOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.aicancode.rateguard.domain.ApiException;
import org.junit.jupiter.api.Test;

/** M1: API keys are random, shown once, stored as a hash; the admin token is compared in constant time. */
class SecurityTest {

    @Test
    void createsUniqueKeysWithTheDocumentedShape() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            keys.add(ApiKeys.newApiKey());
        }
        assertThat(keys).hasSize(200).allMatch(ApiKeys::looksLikeApiKey);
    }

    @Test
    void hashesTheWholeKeyWithHexSha256() throws Exception {
        String key = "rg_" + "ab".repeat(16);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(StandardCharsets.UTF_8)));
        assertThat(ApiKeys.hash(key)).isEqualTo(expected).hasSize(64);
    }

    @Test
    void keepsAShortRecognisablePrefix() {
        assertThat(ApiKeys.prefix("rg_0123456789abcdef0123456789abcdef")).isEqualTo("rg_012345");
    }

    @Test
    void rejectsMalformedKeys() {
        for (String bad : Arrays.asList(null, "", "rg_123", "RG_" + "a".repeat(32), "rg_" + "A".repeat(32),
                "rg_" + "a".repeat(33))) {
            assertThat(ApiKeys.looksLikeApiKey(bad)).as(String.valueOf(bad)).isFalse();
        }
    }

    @Test
    void comparesInConstantTime() {
        assertThat(ApiKeys.constantTimeEquals("same-secret", "same-secret")).isTrue();
        assertThat(ApiKeys.constantTimeEquals("same-secret", "same-secreT")).isFalse();
        assertThat(ApiKeys.constantTimeEquals("short", "a-much-longer-value")).isFalse();
    }

    @Test
    void acceptsOnlyTheExactBearerToken() {
        AdminAuth auth = new AdminAuth("the-admin-token-0123456789-abcdefghij");
        auth.require("Bearer the-admin-token-0123456789-abcdefghij");
        for (String bad : Arrays.asList(null, "", "the-admin-token-0123456789-abcdefghij", "Basic abc", "Bearer wrong",
                "Bearer ")) {
            assertThat(statusOf(() -> auth.require(bad))).isEqualTo(List.of(401, "unauthorized"));
            assertThatThrownBy(() -> auth.require(bad)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.headers()).isEqualTo(Map.of("WWW-Authenticate", "Bearer")));
        }
    }

    @Test
    void keepsAConfiguredSecretAndInventsOneOtherwise() {
        assertThat(ApiKeys.resolveSecret("configured", "ADMIN_TOKEN")).isEqualTo("configured");
        String a = ApiKeys.resolveSecret("", "ADMIN_TOKEN");
        assertThat(a).isNotEqualTo(ApiKeys.resolveSecret(null, "ADMIN_TOKEN")).hasSizeGreaterThanOrEqualTo(32);
    }
}
