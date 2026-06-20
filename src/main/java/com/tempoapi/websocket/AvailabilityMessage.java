package com.tempoapi.websocket;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The payload pushed to {@code /topic/spaces/{spaceId}/availability} subscribers when a
 * resource slot changes state.
 */
public record AvailabilityMessage(
        UUID resourceId,
        String resourceName,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        AvailabilityState state) {

    public static AvailabilityMessage of(SlotChange slot, AvailabilityState state) {
        return new AvailabilityMessage(
                slot.resourceId(),
                slot.resourceName(),
                slot.startTime(),
                slot.endTime(),
                state);
    }
}
