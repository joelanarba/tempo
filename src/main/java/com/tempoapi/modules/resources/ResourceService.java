package com.tempoapi.modules.resources;

import com.tempoapi.common.PageResponse;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Space;
import com.tempoapi.exception.ResourceNotFoundException;
import com.tempoapi.exception.ValidationException;
import com.tempoapi.modules.resources.dto.CreateResourceRequest;
import com.tempoapi.modules.resources.dto.ResourceResponse;
import com.tempoapi.modules.resources.dto.UpdateResourceRequest;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ResourceService {

    private final ResourceRepository resourceRepository;
    private final SpaceRepository spaceRepository;

    public ResourceService(ResourceRepository resourceRepository, SpaceRepository spaceRepository) {
        this.resourceRepository = resourceRepository;
        this.spaceRepository = spaceRepository;
    }

    /** Creates a resource (desk or room) within a space. */
    @Transactional
    public ResourceResponse create(UUID spaceId, CreateResourceRequest request) {
        Space space = requireSpace(spaceId);
        Integer capacity = normaliseCapacity(request.type(), request.capacity());
        Resource resource = new Resource(space, request.name(), request.type(), capacity);
        return ResourceResponse.from(resourceRepository.save(resource));
    }

    /** Returns a page of active resources within a space. */
    @Transactional(readOnly = true)
    public PageResponse<ResourceResponse> listForSpace(UUID spaceId, Pageable pageable) {
        requireSpace(spaceId);
        return PageResponse.from(
                resourceRepository.findBySpaceIdAndActiveTrue(spaceId, pageable).map(ResourceResponse::from));
    }

    /** Returns a single resource by id. */
    @Transactional(readOnly = true)
    public ResourceResponse getById(UUID id) {
        return ResourceResponse.from(findOrThrow(id));
    }

    /** Updates the mutable fields of a resource. */
    @Transactional
    public ResourceResponse update(UUID id, UpdateResourceRequest request) {
        Resource resource = findOrThrow(id);
        resource.setName(request.name());
        resource.setType(request.type());
        resource.setCapacity(normaliseCapacity(request.type(), request.capacity()));
        return ResourceResponse.from(resource);
    }

    /** Soft-deactivates a resource; existing bookings are preserved. */
    @Transactional
    public void deactivate(UUID id) {
        findOrThrow(id).setActive(false);
    }

    private Space requireSpace(UUID spaceId) {
        return spaceRepository.findById(spaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Space not found"));
    }

    private Resource findOrThrow(UUID id) {
        return resourceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Resource not found"));
    }

    private Integer normaliseCapacity(ResourceType type, Integer capacity) {
        if (type == ResourceType.ROOM) {
            if (capacity == null || capacity < 1) {
                throw new ValidationException("capacity is required and must be at least 1 for a ROOM");
            }
            return capacity;
        }
        // Desks are single-occupancy; capacity is not meaningful.
        return null;
    }
}
