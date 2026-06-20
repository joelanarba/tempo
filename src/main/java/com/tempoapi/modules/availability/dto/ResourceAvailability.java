package com.tempoapi.modules.availability.dto;

import com.tempoapi.entity.ResourceType;

import java.util.List;
import java.util.UUID;

public record ResourceAvailability(
        UUID resourceId,
        String resourceName,
        ResourceType type,
        Integer capacity,
        List<BookedSlot> bookedSlots) {
}
