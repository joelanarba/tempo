package com.tempoapi.modules.availability;

import com.tempoapi.AbstractIntegrationTest;
import com.tempoapi.entity.*;
import com.tempoapi.exception.ResourceNotFoundException;
import com.tempoapi.modules.bookings.BookingService;
import com.tempoapi.modules.bookings.dto.CreateBookingRequest;
import com.tempoapi.modules.resources.ResourceService;
import com.tempoapi.modules.resources.dto.UpdateResourceRequest;
import com.tempoapi.modules.spaces.SpaceService;
import com.tempoapi.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class AvailabilityConsistencyTest extends AbstractIntegrationTest {
    @Autowired AvailabilityService availability;
    @Autowired AvailabilityCache cache;
    @Autowired BookingService bookings;
    @Autowired ResourceService resources;
    @Autowired SpaceService spaces;
    @Autowired SpaceRepository spaceRepository;
    @Autowired ResourceRepository resourceRepository;

    @Test
    void lateOldCacheFillCannotHideCommittedBooking() {
        Space space = spaceRepository.save(new Space("Hub", "UTC", "", LocalTime.of(8, 0), LocalTime.of(20, 0)));
        Resource resource = resourceRepository.save(new Resource(space, "Desk", ResourceType.DESK, null));
        LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        var old = availability.getSnapshot(space.getId(), date);
        var user = persistUser(Role.MEMBER);
        bookings.createBooking(user.getId(), new CreateBookingRequest(resource.getId(),
                date.atTime(9, 0).atOffset(ZoneOffset.UTC), date.atTime(10, 0).atOffset(ZoneOffset.UTC)));
        cache.put(space.getId(), date, old);
        var current = availability.getSnapshot(space.getId(), date);
        assertThat(current.revision()).isGreaterThan(old.revision());
        assertThat(current.resources().get(0).bookedSlots()).hasSize(1);
    }

    @Test
    void administrativeChangesInvalidateWarmSnapshots() {
        Space space = spaceRepository.save(new Space("Hub", "UTC", "", LocalTime.of(8, 0), LocalTime.of(20, 0)));
        Resource resource = resourceRepository.save(new Resource(space, "Desk", ResourceType.DESK, null));
        LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        var old = availability.getSnapshot(space.getId(), date);
        resources.update(resource.getId(), new UpdateResourceRequest("Renamed", ResourceType.DESK, null));
        var renamed = availability.getSnapshot(space.getId(), date);
        assertThat(renamed.revision()).isGreaterThan(old.revision());
        assertThat(renamed.resources().get(0).resourceName()).isEqualTo("Renamed");
        resources.deactivate(resource.getId());
        assertThat(availability.getSnapshot(space.getId(), date).resources()).isEmpty();
        spaces.deactivate(space.getId());
        assertThatThrownBy(() -> availability.getSnapshot(space.getId(), date)).isInstanceOf(ResourceNotFoundException.class);
    }
}
