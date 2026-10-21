package com.tempoapi.modules.bookings;

import com.tempoapi.entity.Booking;
import com.tempoapi.entity.BookingStatus;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.exception.ResourceNotFoundException;
import com.tempoapi.exception.ValidationException;
import com.tempoapi.modules.availability.AvailabilityChanges;
import java.time.Clock;
import org.mockito.Spy;
import com.tempoapi.modules.bookings.dto.BookingResponse;
import com.tempoapi.modules.bookings.dto.CreateBookingRequest;
import com.tempoapi.modules.waitlist.WaitlistPromoter;
import com.tempoapi.repository.BookingRepository;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import com.tempoapi.repository.UserRepository;
import com.tempoapi.util.TimeRangeUtil;
import com.tempoapi.websocket.AvailabilityEventPublisher;
import com.tempoapi.websocket.SlotChange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private ResourceRepository resourceRepository;
    @Mock
    private SpaceRepository spaceRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AvailabilityChanges availabilityChanges;
    @Spy
    private Clock clock = Clock.systemUTC();
    @Mock
    private WaitlistPromoter waitlistPromoter;
    @InjectMocks
    private BookingService bookingService;

    private OffsetDateTime tomorrowAt(int hour) {
        return LocalDate.now().plusDays(1).atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    private Space activeSpace() {
        Space space = new Space("Hub", "Accra", "desc", LocalTime.of(8, 0), LocalTime.of(20, 0));
        space.setId(UUID.randomUUID());
        return space;
    }

    private Resource activeResource(Space space) {
        Resource resource = new Resource(space, "Desk 1", ResourceType.DESK, null);
        resource.setId(UUID.randomUUID());
        return resource;
    }

    @Test
    void rejectsEndBeforeStart() {
        UUID userId = UUID.randomUUID();
        CreateBookingRequest request =
                new CreateBookingRequest(UUID.randomUUID(), tomorrowAt(11), tomorrowAt(10));

        assertThatThrownBy(() -> bookingService.createBooking(userId, request))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsStartInThePast() {
        UUID userId = UUID.randomUUID();
        OffsetDateTime past = LocalDate.now().minusDays(1).atTime(9, 0).atOffset(ZoneOffset.UTC);
        CreateBookingRequest request =
                new CreateBookingRequest(UUID.randomUUID(), past, past.plusHours(1));

        assertThatThrownBy(() -> bookingService.createBooking(userId, request))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsBookingBeforeOpeningTime() {
        Space space = activeSpace();
        Resource resource = activeResource(space);
        when(resourceRepository.findByIdForUpdate(resource.getId())).thenReturn(Optional.of(resource));
        CreateBookingRequest request =
                new CreateBookingRequest(resource.getId(), tomorrowAt(7), tomorrowAt(8));

        assertThatThrownBy(() -> bookingService.createBooking(UUID.randomUUID(), request))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsWhenResourceMissing() {
        UUID resourceId = UUID.randomUUID();
        when(resourceRepository.findByIdForUpdate(resourceId)).thenReturn(Optional.empty());
        CreateBookingRequest request =
                new CreateBookingRequest(resourceId, tomorrowAt(9), tomorrowAt(10));

        assertThatThrownBy(() -> bookingService.createBooking(UUID.randomUUID(), request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createsBookingAndPublishesBookedEvent() {
        Space space = activeSpace();
        Resource resource = activeResource(space);
        UUID userId = UUID.randomUUID();
        User user = new User("e@tempo.africa", "hash", "First", "Last", Role.MEMBER);
        user.setId(userId);

        OffsetDateTime start = tomorrowAt(9);
        OffsetDateTime end = tomorrowAt(10);
        CreateBookingRequest request = new CreateBookingRequest(resource.getId(), start, end);

        Booking saved = new Booking(resource, user, space, TimeRangeUtil.toRange(start, end), BookingStatus.CONFIRMED);
        saved.setId(UUID.randomUUID());

        when(resourceRepository.findByIdForUpdate(resource.getId())).thenReturn(Optional.of(resource));
        when(bookingRepository.findOverlapping(eq(resource.getId()), any(), any())).thenReturn(List.of());
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenReturn(saved);

        BookingResponse response = bookingService.createBooking(userId, request);

        assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(response.resourceId()).isEqualTo(resource.getId());
        verify(availabilityChanges).booked(any(SlotChange.class));
    }

    @Test
    void cancelDelegatesToWaitlistPromoter() {
        Space space = activeSpace();
        Resource resource = activeResource(space);
        UUID userId = UUID.randomUUID();
        User user = new User("e@tempo.africa", "hash", "First", "Last", Role.MEMBER);
        user.setId(userId);

        OffsetDateTime start = tomorrowAt(9);
        OffsetDateTime end = tomorrowAt(10);
        Booking booking = new Booking(resource, user, space, TimeRangeUtil.toRange(start, end), BookingStatus.CONFIRMED);
        booking.setId(UUID.randomUUID());

        when(bookingRepository.findResourceIdById(booking.getId())).thenReturn(Optional.of(resource.getId()));
        when(resourceRepository.findByIdForUpdate(resource.getId())).thenReturn(Optional.of(resource));
        when(bookingRepository.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));

        bookingService.cancelBooking(userId, booking.getId());

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(waitlistPromoter).promoteForReleasedSlot(any(SlotChange.class));
    }

    @Test
    void rejectsOffsetRepresentationOutsideUtcOpeningHours() {
        Space space = activeSpace();
        Resource resource = activeResource(space);
        when(resourceRepository.findByIdForUpdate(resource.getId())).thenReturn(Optional.of(resource));
        OffsetDateTime local = tomorrowAt(18).withOffsetSameLocal(java.time.ZoneOffset.ofHours(-8));
        assertThatThrownBy(() -> bookingService.createBooking(UUID.randomUUID(),
                new CreateBookingRequest(resource.getId(), local, local.plusHours(1))))
                .isInstanceOf(ValidationException.class);
        org.mockito.Mockito.verify(bookingRepository, org.mockito.Mockito.never()).saveAndFlush(any());
    }
}
