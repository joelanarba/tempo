package com.tempoapi.util;

import io.hypersistence.utils.hibernate.type.range.Range;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Builds and reads the {@code [start, end)} time ranges used for bookings. The
 * half-open convention (start inclusive, end exclusive) is what makes touching
 * slots such as 09:00-10:00 and 10:00-11:00 count as adjacent rather than
 * overlapping, and it must match the GiST exclusion constraint in the database.
 */
public final class TimeRangeUtil {

    private TimeRangeUtil() {
    }

    /** Builds a half-open {@code [start, end)} range in UTC from the supplied instants. */
    public static Range<ZonedDateTime> toRange(OffsetDateTime start, OffsetDateTime end) {
        return Range.closedOpen(toUtcZoned(start), toUtcZoned(end));
    }

    /** Normalises an offset date-time to UTC, preserving the instant. */
    public static OffsetDateTime toUtc(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static ZonedDateTime toUtcZoned(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.UTC).toZonedDateTime();
    }

    /** Start of the given calendar day in UTC. */
    public static OffsetDateTime startOfDayUtc(LocalDate date) {
        return date.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    /** Start of the day after the given calendar day in UTC (exclusive upper bound). */
    public static OffsetDateTime startOfNextDayUtc(LocalDate date) {
        return date.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    /** Reads the lower bound of a range as an offset date-time, or null if unbounded. */
    public static OffsetDateTime lower(Range<ZonedDateTime> range) {
        return range.lower() == null ? null : range.lower().toOffsetDateTime();
    }

    /** Reads the upper bound of a range as an offset date-time, or null if unbounded. */
    public static OffsetDateTime upper(Range<ZonedDateTime> range) {
        return range.upper() == null ? null : range.upper().toOffsetDateTime();
    }
}
