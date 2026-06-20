package com.tempoapi.modules.spaces.dto;

import com.tempoapi.entity.Space;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

public record SpaceResponse(
        UUID id,
        String name,
        String location,
        String description,
        LocalTime openingTime,
        LocalTime closingTime,
        boolean active,
        Instant createdAt) {

    public static SpaceResponse from(Space space) {
        return new SpaceResponse(
                space.getId(),
                space.getName(),
                space.getLocation(),
                space.getDescription(),
                space.getOpeningTime(),
                space.getClosingTime(),
                space.isActive(),
                space.getCreatedAt());
    }
}
