package com.tempoapi.websocket;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The minimal description of a resource slot whose availability has changed.
 * The publisher decides which topic it goes to (from {@code spaceId}) and adds
 * the {@link AvailabilityState}.
 */
public record SlotChange(
        UUID spaceId,
        UUID resourceId,
        String resourceName,
        OffsetDateTime startTime,
        OffsetDateTime endTime) {
}
