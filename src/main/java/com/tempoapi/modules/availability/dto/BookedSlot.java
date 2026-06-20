package com.tempoapi.modules.availability.dto;

import java.time.OffsetDateTime;

public record BookedSlot(
        OffsetDateTime startTime,
        OffsetDateTime endTime) {
}
