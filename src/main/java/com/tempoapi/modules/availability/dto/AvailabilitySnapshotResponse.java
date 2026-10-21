package com.tempoapi.modules.availability.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The full availability picture for a space on a date: every active resource and the
 * CONFIRMED or HELD slots, with the database revision used by its Redis cache key.
 */
public record AvailabilitySnapshotResponse(
        UUID spaceId,
        LocalDate date,
        long revision,
        List<ResourceAvailability> resources) {
}
