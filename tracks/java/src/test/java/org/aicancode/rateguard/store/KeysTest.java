package org.aicancode.rateguard.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.aicancode.rateguard.domain.Models.Algorithm;
import org.junit.jupiter.api.Test;

/** M3: the Redis key layout (spec/architecture.md, ADR-0003). */
class KeysTest {

    private static Pattern glob(String pattern) {
        return Pattern.compile("^" + Pattern.quote(pattern).replace("*", "\\E.*\\Q") + "$");
    }

    @Test
    void namesTenantsAndApiKeys() {
        assertThat(Keys.TENANTS).isEqualTo("rg:tenants");
        assertThat(Keys.tenant("acme")).isEqualTo("rg:tenant:acme");
        assertThat(Keys.apiKey("ab12")).isEqualTo("rg:key:ab12");
    }

    @Test
    void putsTheTenantHashTagInEveryLimiterKey() {
        assertThat(Keys.limiter("acme", Algorithm.TOKEN_BUCKET, "orders.create")).isEqualTo("rg:{acme}:tb:orders.create");
        assertThat(Keys.limiter("acme", Algorithm.SLIDING_WINDOW, "default")).isEqualTo("rg:{acme}:sw:default");
        assertThat(Keys.usage("acme")).isEqualTo("rg:{acme}:usage");
    }

    @Test
    void scansOneTenantOnly() {
        Pattern p = glob(Keys.tenantPattern("acme"));
        assertThat(p.matcher("rg:{acme}:tb:x").matches()).isTrue();
        assertThat(p.matcher("rg:{acme-2}:tb:x").matches()).isFalse();
        assertThat(p.matcher("rg:tenant:acme").matches()).isFalse();
    }

    @Test
    void mapsMinutesToRingSlots() {
        assertThat(Keys.USAGE_SLOTS).isEqualTo(180);
        assertThat(Keys.usageSlot(29_848_884L)).isEqualTo(29_848_884 % 180);
        assertThat(Keys.usageSlot(180)).isZero();
    }
}
