package org.aicancode.rateguard.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** Time helpers. Services never call Instant.now() directly: they get a Clock, so tests can travel in time. */
public final class Times {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private Times() {
    }

    /** Millisecond precision everywhere: the API and the stored timestamps agree. */
    public static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    /** ISO-8601 UTC with millisecond precision and a 'Z' suffix, identical in every track. */
    public static String iso(Instant instant) {
        return instant == null ? null : ISO.format(instant);
    }

    public static String isoMs(long epochMs) {
        return iso(Instant.ofEpochMilli(epochMs));
    }
}
