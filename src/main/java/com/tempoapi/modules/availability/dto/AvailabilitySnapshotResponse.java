package com.tempoapi.modules.availability.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The full availability picture for a space on a date: every active resource and the
 * CONFIRMED slots already booked against it. This read-heavy response is cached in
 * Redis (Sprint 5) keyed by {@code availability:{spaceId}:{date}}.
 */
public record AvailabilitySnapshotResponse(
        UUID spaceId,
        LocalDate date,
        List<ResourceAvailability> resources) {
}
