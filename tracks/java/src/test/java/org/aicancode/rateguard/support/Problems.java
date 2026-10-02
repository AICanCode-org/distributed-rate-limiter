package org.aicancode.rateguard.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.aicancode.rateguard.domain.ApiException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/** statusOf(() -> service.call()) -> [status, slug] of the ApiException it throws. */
public final class Problems {

    private Problems() {
    }

    public static List<Object> statusOf(ThrowingCallable call) {
        ApiException[] caught = new ApiException[1];
        assertThatThrownBy(call).isInstanceOf(ApiException.class).satisfies(e -> caught[0] = (ApiException) e);
        return List.of(caught[0].status(), caught[0].slug());
    }

    /** Expects a 400 validation-error and returns its detail message. */
    public static String message(ThrowingCallable call) {
        ApiException[] caught = new ApiException[1];
        assertThatThrownBy(call).isInstanceOf(ApiException.class).satisfies(e -> caught[0] = (ApiException) e);
        assertThat(caught[0].status()).isEqualTo(400);
        assertThat(caught[0].slug()).isEqualTo("validation-error");
        return caught[0].detail();
    }

    /** [status, slug, detail] of the ApiException it throws. */
    public static List<Object> problem(ThrowingCallable call) {
        ApiException[] caught = new ApiException[1];
        assertThatThrownBy(call).isInstanceOf(ApiException.class).satisfies(e -> caught[0] = (ApiException) e);
        return List.of(caught[0].status(), caught[0].slug(), String.valueOf(caught[0].detail()));
    }

    public static int codeOf(ThrowingCallable call) {
        return (int) statusOf(call).get(0);
    }
}
