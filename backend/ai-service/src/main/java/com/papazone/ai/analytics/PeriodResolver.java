package com.papazone.ai.analytics;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Turns a Period into a concrete [from, to) range in the restaurant's time zone. */
public final class PeriodResolver {

    /** from inclusive, to exclusive. firstDay/lastDay are the calendar days covered (for display). */
    public record Range(Instant from, Instant to, LocalDate firstDay, LocalDate lastDay) {
    }

    private PeriodResolver() {
    }

    public static Range resolve(Period period, Clock clock, ZoneId zone) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate tomorrow = today.plusDays(1);

        LocalDate first;
        LocalDate endExclusive;

        switch (period) {
            case TODAY -> {
                first = today;
                endExclusive = tomorrow;
            }
            case YESTERDAY -> {
                first = today.minusDays(1);
                endExclusive = today;
            }
            case LAST_7_DAYS -> {
                first = today.minusDays(6);
                endExclusive = tomorrow;
            }
            case LAST_30_DAYS -> {
                first = today.minusDays(29);
                endExclusive = tomorrow;
            }
            case THIS_MONTH -> {
                first = today.withDayOfMonth(1);
                endExclusive = tomorrow;
            }
            case LAST_MONTH -> {
                first = today.withDayOfMonth(1).minusMonths(1);
                endExclusive = today.withDayOfMonth(1);
            }
            case ALL_TIME -> {
                first = LocalDate.of(2000, 1, 1);
                endExclusive = tomorrow;
            }
            default -> throw new IllegalArgumentException("Unsupported period " + period);
        }

        return new Range(
                first.atStartOfDay(zone).toInstant(),
                endExclusive.atStartOfDay(zone).toInstant(),
                first,
                endExclusive.minusDays(1)
        );
    }
}
