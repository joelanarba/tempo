package com.tempoapi.modules.resources.dto;

import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;

import java.time.Instant;
import java.util.UUID;

public record ResourceResponse(
        UUID id,
        UUID spaceId,
        String name,
        ResourceType type,
        Integer capacity,
        boolean active,
        Instant createdAt) {

    public static ResourceResponse from(Resource resource) {
        return new ResourceResponse(
                resource.getId(),
                resource.getSpace().getId(),
                resource.getName(),
                resource.getType(),
                resource.getCapacity(),
                resource.isActive(),
                resource.getCreatedAt());
    }
}
