package com.tempoapi.modules.spaces;

import com.tempoapi.common.PageResponse;
import com.tempoapi.entity.Space;
import com.tempoapi.exception.ResourceNotFoundException;
import com.tempoapi.exception.ValidationException;
import com.tempoapi.modules.spaces.dto.CreateSpaceRequest;
import com.tempoapi.modules.spaces.dto.SpaceResponse;
import com.tempoapi.modules.spaces.dto.UpdateSpaceRequest;
import com.tempoapi.repository.SpaceRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.UUID;

@Service
public class SpaceService {

    private final SpaceRepository spaceRepository;

    public SpaceService(SpaceRepository spaceRepository) {
        this.spaceRepository = spaceRepository;
    }

    /** Creates a new active space. */
    @Transactional
    public SpaceResponse create(CreateSpaceRequest request) {
        validateHours(request.openingTime(), request.closingTime());
        Space space = new Space(
                request.name(),
                request.location(),
                request.description(),
                request.openingTime(),
                request.closingTime());
        return SpaceResponse.from(spaceRepository.save(space));
    }

    /** Returns a page of active spaces. */
    @Transactional(readOnly = true)
    public PageResponse<SpaceResponse> list(Pageable pageable) {
        return PageResponse.from(spaceRepository.findByActiveTrue(pageable).map(SpaceResponse::from));
    }

    /** Returns a single space by id. */
    @Transactional(readOnly = true)
    public SpaceResponse getById(UUID id) {
        return SpaceResponse.from(findOrThrow(id));
    }

    /** Updates the mutable fields of a space. */
    @Transactional
    public SpaceResponse update(UUID id, UpdateSpaceRequest request) {
        validateHours(request.openingTime(), request.closingTime());
        Space space = findOrThrow(id);
        space.setName(request.name());
        space.setLocation(request.location());
        space.setDescription(request.description());
        space.setOpeningTime(request.openingTime());
        space.setClosingTime(request.closingTime());
        return SpaceResponse.from(space);
    }

    /** Soft-deactivates a space; existing rows and bookings are preserved. */
    @Transactional
    public void deactivate(UUID id) {
        findOrThrow(id).setActive(false);
    }

    /** Returns the managed entity for use by other services. */
    @Transactional(readOnly = true)
    public Space requireActiveSpace(UUID id) {
        Space space = findOrThrow(id);
        if (!space.isActive()) {
            throw new ResourceNotFoundException("Space not found");
        }
        return space;
    }

    private Space findOrThrow(UUID id) {
        return spaceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Space not found"));
    }

    private void validateHours(LocalTime openingTime, LocalTime closingTime) {
        if (!openingTime.isBefore(closingTime)) {
            throw new ValidationException("openingTime must be before closingTime");
        }
    }
}
