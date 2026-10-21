package com.tempoapi.modules.availability;

import com.tempoapi.entity.Booking;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.Space;
import com.tempoapi.exception.ResourceNotFoundException;
import com.tempoapi.modules.availability.dto.AvailabilitySnapshotResponse;
import com.tempoapi.modules.availability.dto.BookedSlot;
import com.tempoapi.modules.availability.dto.ResourceAvailability;
import com.tempoapi.repository.BookingRepository;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import com.tempoapi.util.TimeRangeUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AvailabilityService {

    private final SpaceRepository spaceRepository;
    private final ResourceRepository resourceRepository;
    private final BookingRepository bookingRepository;
    private final AvailabilityCache availabilityCache;

    public AvailabilityService(SpaceRepository spaceRepository,
                               ResourceRepository resourceRepository,
                               BookingRepository bookingRepository,
                               AvailabilityCache availabilityCache) {
        this.spaceRepository = spaceRepository;
        this.resourceRepository = resourceRepository;
        this.bookingRepository = bookingRepository;
        this.availabilityCache = availabilityCache;
    }

    /**
     * The revision, resource list and occupancy query share one PostgreSQL snapshot.
     * Cache entries from earlier revisions can expire without explicit key deletion.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AvailabilitySnapshotResponse getSnapshot(UUID spaceId, LocalDate date) {
        Space space = spaceRepository.findById(spaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Space not found"));
        if (!space.isActive()) {
            throw new ResourceNotFoundException("Space not found");
        }
        Optional<AvailabilitySnapshotResponse> cached = availabilityCache.get(spaceId, date, space.getAvailabilityRevision());
        if (cached.isPresent()) {
            return cached.get();
        }

        OffsetDateTime windowStart = TimeRangeUtil.startOfDayUtc(date);
        OffsetDateTime windowEnd = TimeRangeUtil.startOfNextDayUtc(date);

        List<Resource> resources = resourceRepository.findBySpaceIdAndActiveTrueOrderByName(spaceId);
        List<Booking> bookings = bookingRepository.findOccupiedInSpaceForWindow(spaceId, windowStart, windowEnd);

        Map<UUID, List<BookedSlot>> slotsByResource = new HashMap<>();
        for (Booking booking : bookings) {
            slotsByResource.computeIfAbsent(booking.getResource().getId(), key -> new ArrayList<>())
                    .add(new BookedSlot(
                            TimeRangeUtil.lower(booking.getTimeRange()),
                            TimeRangeUtil.upper(booking.getTimeRange())));
        }

        List<ResourceAvailability> resourceAvailabilities = new ArrayList<>();
        for (Resource resource : resources) {
            resourceAvailabilities.add(new ResourceAvailability(
                    resource.getId(),
                    resource.getName(),
                    resource.getType(),
                    resource.getCapacity(),
                    slotsByResource.getOrDefault(resource.getId(), List.of())));
        }

        AvailabilitySnapshotResponse snapshot =
                new AvailabilitySnapshotResponse(spaceId, date, space.getAvailabilityRevision(), resourceAvailabilities);
        availabilityCache.put(spaceId, date, snapshot);
        return snapshot;
    }
}
