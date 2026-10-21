package com.tempoapi.util;

import com.tempoapi.entity.Space;
import com.tempoapi.exception.ValidationException;

import java.time.Instant;
import java.time.OffsetDateTime;

public final class BookingTimes {
    private BookingTimes() { }

    public static void validateFuture(OffsetDateTime start, OffsetDateTime end, Instant now) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new ValidationException("startTime must be before endTime");
        }
        if (!start.toInstant().isAfter(now)) {
            throw new ValidationException("startTime must be in the future");
        }
    }

    public static void validateHours(Space space, OffsetDateTime start, OffsetDateTime end) {
        OffsetDateTime utcStart = TimeRangeUtil.toUtc(start);
        OffsetDateTime utcEnd = TimeRangeUtil.toUtc(end);
        if (!utcStart.toLocalDate().equals(utcEnd.toLocalDate())) {
            throw new ValidationException("A booking must start and end on the same UTC day");
        }
        if (utcStart.toLocalTime().isBefore(space.getOpeningTime())) {
            throw new ValidationException("Booking starts before the space opening time");
        }
        if (utcEnd.toLocalTime().isAfter(space.getClosingTime())) {
            throw new ValidationException("Booking ends after the space closing time");
        }
    }
}
