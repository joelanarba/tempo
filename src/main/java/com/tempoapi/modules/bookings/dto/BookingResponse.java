package com.tempoapi.modules.bookings.dto;

import com.tempoapi.entity.Booking;
import com.tempoapi.entity.BookingStatus;
import com.tempoapi.util.TimeRangeUtil;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

public record BookingResponse(
        UUID id,
        UUID resourceId,
        String resourceName,
        UUID spaceId,
        UUID userId,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        BookingStatus status,
        Instant createdAt) {

    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getResource().getId(),
                booking.getResource().getName(),
                booking.getSpace().getId(),
                booking.getUser().getId(),
                TimeRangeUtil.lower(booking.getTimeRange()),
                TimeRangeUtil.upper(booking.getTimeRange()),
                booking.getStatus(),
                booking.getCreatedAt());
    }
}
