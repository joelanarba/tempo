package com.tempoapi.modules.availability;

import com.tempoapi.AbstractIntegrationTest;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.modules.bookings.BookingService;
import com.tempoapi.modules.bookings.dto.CreateBookingRequest;
import com.tempoapi.repository.BookingRepository;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AvailabilityCacheTest extends AbstractIntegrationTest {

    @Autowired
    private AvailabilityService availabilityService;
    @Autowired
    private BookingService bookingService;
    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private ResourceRepository resourceRepository;
    @Autowired
    private AvailabilityCache availabilityCache;
    @SpyBean
    private BookingRepository bookingRepository;

    private UUID spaceId;
    private UUID resourceId;
    private UUID userId;
    private LocalDate date;

    @BeforeEach
    void seed() {
        Space space = spaceRepository.save(new Space(
                "Hub", "Accra", "desc", LocalTime.of(8, 0), LocalTime.of(20, 0)));
        Resource resource = resourceRepository.save(new Resource(space, "Desk 1", ResourceType.DESK, null));
        User user = persistUser(Role.MEMBER);
        spaceId = space.getId();
        resourceId = resource.getId();
        userId = user.getId();
        date = LocalDate.now().plusDays(1);
        // Ensure a clean cache slate for this space/date.
    }

    private OffsetDateTime at(int hour) {
        return date.atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    @Test
    void secondReadWithinTtlIsServedFromCacheAndDoesNotHitTheDatabase() {
        availabilityService.getSnapshot(spaceId, date);
        availabilityService.getSnapshot(spaceId, date);

        verify(bookingRepository, times(1)).findOccupiedInSpaceForWindow(eq(spaceId), any(), any());
    }

    @Test
    void bookingEvictsTheCacheForcingAFreshRead() {
        availabilityService.getSnapshot(spaceId, date);

        bookingService.createBooking(userId, new CreateBookingRequest(resourceId, at(9), at(10)));

        availabilityService.getSnapshot(spaceId, date);

        // First read (miss) and the post-eviction read (miss) both query; the create itself
        // does not call this query method.
        verify(bookingRepository, times(2)).findOccupiedInSpaceForWindow(eq(spaceId), any(), any());
    }
}
