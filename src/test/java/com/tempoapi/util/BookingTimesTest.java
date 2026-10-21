package com.tempoapi.util;

import com.tempoapi.entity.Space;
import com.tempoapi.exception.ValidationException;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class BookingTimesTest {
    private final Space space = new Space("Hub", "UTC", "", LocalTime.of(8, 0), LocalTime.of(20, 0));

    @Test
    void acceptsEquivalentInstantsWithDifferentOffsets() {
        BookingTimes.validateHours(space, OffsetDateTime.parse("2027-01-01T11:00:00+02:00"),
                OffsetDateTime.parse("2027-01-01T12:00:00+02:00"));
        BookingTimes.validateHours(space, OffsetDateTime.parse("2027-01-01T09:00:00Z"),
                OffsetDateTime.parse("2027-01-01T10:00:00Z"));
    }

    @Test
    void rejectsLocalHoursThatNormalizeOutsideUtcOpeningHours() {
        assertThatThrownBy(() -> BookingTimes.validateHours(space,
                OffsetDateTime.parse("2027-01-01T18:00:00-08:00"),
                OffsetDateTime.parse("2027-01-01T19:00:00-08:00"))).isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsIntervalsThatCrossUtcMidnight() {
        assertThatThrownBy(() -> BookingTimes.validateHours(space,
                OffsetDateTime.parse("2027-01-01T12:00:00+13:00"),
                OffsetDateTime.parse("2027-01-01T14:00:00+13:00"))).isInstanceOf(ValidationException.class);
    }

    @Test
    void permitsExactOpeningAndClosingBoundaries() {
        BookingTimes.validateHours(space, OffsetDateTime.parse("2027-01-01T08:00:00Z"),
                OffsetDateTime.parse("2027-01-01T20:00:00Z"));
    }

    @Test
    void rejectsStartAtTheCurrentInstantAndEmptyIntervals() {
        OffsetDateTime start = OffsetDateTime.parse("2027-01-01T09:00:00Z");
        assertThatThrownBy(() -> BookingTimes.validateFuture(start, start.plusHours(1), start.toInstant()))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> BookingTimes.validateFuture(start, start, start.minusDays(1).toInstant()))
                .isInstanceOf(ValidationException.class);
    }
}
