package com.tempoapi.websocket;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Private payload pushed to /user/queue/waitlist for one member: an offer to claim a
 * freed slot (with its expiry), or confirmation that a waitlisted slot was granted.
 */
public record WaitlistMessage(
        UUID entryId,
        UUID resourceId,
        String resourceName,
        UUID spaceId,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        Instant expiresAt,
        WaitlistState state) {
}
