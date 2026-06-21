package com.tempoapi.modules.waitlist.dto;

import com.tempoapi.entity.WaitlistEntry;
import com.tempoapi.entity.WaitlistStatus;
import com.tempoapi.util.TimeRangeUtil;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

public record WaitlistEntryResponse(
        UUID id,
        UUID resourceId,
        String resourceName,
        UUID spaceId,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        WaitlistStatus status,
        Integer position,
        Instant offerExpiresAt,
        Instant createdAt) {

    public static WaitlistEntryResponse from(WaitlistEntry entry, Integer position) {
        Instant offerExpiresAt = entry.getHeldBooking() == null
                ? null : entry.getHeldBooking().getHoldExpiresAt();
        return new WaitlistEntryResponse(
                entry.getId(),
                entry.getResource().getId(),
                entry.getResource().getName(),
                entry.getSpace().getId(),
                TimeRangeUtil.lower(entry.getTimeRange()),
                TimeRangeUtil.upper(entry.getTimeRange()),
                entry.getStatus(),
                position,
                offerExpiresAt,
                entry.getCreatedAt());
    }
}
