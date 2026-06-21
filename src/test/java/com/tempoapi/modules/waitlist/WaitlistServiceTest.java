package com.tempoapi.modules.waitlist;

import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.entity.WaitlistEntry;
import com.tempoapi.entity.WaitlistStatus;
import com.tempoapi.exception.SlotAvailableException;
import com.tempoapi.modules.availability.AvailabilityCache;
import com.tempoapi.modules.waitlist.dto.JoinWaitlistRequest;
import com.tempoapi.modules.waitlist.dto.WaitlistEntryResponse;
import com.tempoapi.repository.BookingRepository;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.WaitlistEntryRepository;
import com.tempoapi.repository.UserRepository;
import com.tempoapi.util.TimeRangeUtil;
import com.tempoapi.websocket.AvailabilityEventPublisher;
import com.tempoapi.websocket.WaitlistEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaitlistServiceTest {

    @Mock private WaitlistEntryRepository waitlistEntryRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private ResourceRepository resourceRepository;
    @Mock private UserRepository userRepository;
    @Mock private AvailabilityCache availabilityCache;
    @Mock private AvailabilityEventPublisher availabilityEventPublisher;
    @Mock private WaitlistEventPublisher waitlistEventPublisher;
    @Mock private WaitlistPromoter waitlistPromoter;

    private WaitlistService service() {
        return new WaitlistService(waitlistEntryRepository, bookingRepository, resourceRepository,
                userRepository, availabilityCache, availabilityEventPublisher, waitlistEventPublisher, 120,
                waitlistPromoter);
    }

    private OffsetDateTime at(int hour) {
        return LocalDate.now().plusDays(1).atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    private Space space() {
        Space s = new Space("Hub", "Accra", "d", LocalTime.of(8, 0), LocalTime.of(20, 0));
        s.setId(UUID.randomUUID());
        return s;
    }

    private Resource resource(Space space) {
        Resource r = new Resource(space, "Desk 1", ResourceType.DESK, null);
        r.setId(UUID.randomUUID());
        return r;
    }

    @Test
    void rejectsJoiningWhenSlotIsActuallyFree() {
        Space space = space();
        Resource resource = resource(space);
        when(resourceRepository.findById(resource.getId())).thenReturn(Optional.of(resource));
        when(bookingRepository.findOverlapping(eq(resource.getId()), any(), any())).thenReturn(List.of());

        JoinWaitlistRequest request = new JoinWaitlistRequest(resource.getId(), at(9), at(10));

        assertThatThrownBy(() -> service().join(UUID.randomUUID(), request))
                .isInstanceOf(SlotAvailableException.class);
    }

    @Test
    void joinsWhenSlotIsTaken() {
        Space space = space();
        Resource resource = resource(space);
        UUID userId = UUID.randomUUID();
        User user = new User("e@tempo.africa", "h", "F", "L", Role.MEMBER);
        user.setId(userId);

        when(resourceRepository.findById(resource.getId())).thenReturn(Optional.of(resource));
        when(bookingRepository.findOverlapping(eq(resource.getId()), any(), any()))
                .thenReturn(List.of(new com.tempoapi.entity.Booking()));
        when(waitlistEntryRepository.existsActiveEntry(any(), any(), any(), any())).thenReturn(false);
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(waitlistEntryRepository.save(any(WaitlistEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(waitlistEntryRepository.findByResourceIdAndStatusOrderByCreatedAtAsc(resource.getId(), WaitlistStatus.WAITING))
                .thenReturn(List.of());

        WaitlistEntryResponse response = service().join(userId, new JoinWaitlistRequest(resource.getId(), at(9), at(10)));

        assertThat(response.status()).isEqualTo(WaitlistStatus.WAITING);
        assertThat(response.position()).isEqualTo(1);
    }
}
