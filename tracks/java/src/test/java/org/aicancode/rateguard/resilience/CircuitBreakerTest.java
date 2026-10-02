package org.aicancode.rateguard.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.aicancode.rateguard.domain.DuplicateException;
import org.aicancode.rateguard.domain.StoreUnavailableException;
import org.junit.jupiter.api.Test;

/** v1.1 (Phase 7): the circuit breaker in front of Redis and the LLM. */
class CircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();
    private boolean fail = true;
    private final CircuitBreaker breaker = new CircuitBreaker("redis", 3, 5000, now::get,
            e -> e instanceof StoreUnavailableException, skipped::incrementAndGet);

    private String dep() {
        calls.incrementAndGet();
        if (fail) {
            throw new StoreUnavailableException("connection refused");
        }
        return "ok";
    }

    private void failTimes(int n) {
        for (int i = 0; i < n; i++) {
            assertThatThrownBy(() -> breaker.call(this::dep)).isInstanceOf(StoreUnavailableException.class);
        }
    }

    @Test
    void opensAfterThreeFailuresInARowAndThenSkipsTheCall() {
        failTimes(3);
        assertThat(breaker.state()).isEqualTo("open");
        assertThatThrownBy(() -> breaker.call(this::dep)).isInstanceOf(CircuitOpenException.class);
        assertThat(calls.get()).isEqualTo(3);
        assertThat(skipped.get()).isEqualTo(1);
    }

    @Test
    void aSuccessResetsTheCount() throws Exception {
        failTimes(2);
        fail = false;
        assertThat(breaker.call(this::dep)).isEqualTo("ok");
        fail = true;
        failTimes(2);
        assertThat(breaker.state()).isEqualTo("closed");
    }

    @Test
    void halfOpenTrialSuccessClosesAndFailureReopens() throws Exception {
        failTimes(3);
        now.addAndGet(5000);
        assertThat(breaker.state()).isEqualTo("half-open");
        failTimes(1);
        assertThat(breaker.state()).isEqualTo("open");
        assertThat(calls.get()).isEqualTo(4);
        now.addAndGet(5000);
        fail = false;
        assertThat(breaker.call(this::dep)).isEqualTo("ok");
        assertThat(breaker.state()).isEqualTo("closed");
    }

    @Test
    void onlyOneTrialCallWhileHalfOpen() throws Exception {
        failTimes(3);
        now.addAndGet(5000);
        String result = breaker.call(() -> {
            // a second caller arrives while the trial is still running
            assertThatThrownBy(() -> breaker.call(() -> "second")).isInstanceOf(CircuitOpenException.class);
            return "trial";
        });
        assertThat(result).isEqualTo("trial");
        assertThat(skipped.get()).isEqualTo(1);
    }

    @Test
    void answersThatAreNotFailuresDoNotOpenIt() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> breaker.call(() -> {
                throw new DuplicateException("acme");
            })).isInstanceOf(DuplicateException.class);
        }
        assertThat(breaker.state()).isEqualTo("closed");
    }
}
