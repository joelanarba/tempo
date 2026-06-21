package com.tempoapi.modules.waitlist;

import com.tempoapi.entity.Booking;
import com.tempoapi.entity.BookingStatus;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.entity.WaitlistEntry;
import com.tempoapi.entity.WaitlistStatus;
import com.tempoapi.exception.OfferExpiredException;
import com.tempoapi.modules.availability.AvailabilityCache;
import com.tempoapi.modules.bookings.dto.BookingResponse;
import com.tempoapi.repository.BookingRepository;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.UserRepository;
import com.tempoapi.repository.WaitlistEntryRepository;
import com.tempoapi.util.TimeRangeUtil;
import com.tempoapi.websocket.AvailabilityEventPublisher;
import com.tempoapi.websocket.SlotChange;
import com.tempoapi.websocket.WaitlistEventPublisher;
import com.tempoapi.websocket.WaitlistMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
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
class WaitlistPromotionTest {

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
    void promotionOffersHeldSlotToFirstWaiter() {
        Space space = space();
        Resource resource = resource(space);
        User waiter = new User("w@tempo.africa", "h", "F", "L", Role.MEMBER);
        waiter.setId(UUID.randomUUID());
        WaitlistEntry entry = new WaitlistEntry(resource, space, waiter,
                TimeRangeUtil.toRange(at(9), at(10)), WaitlistStatus.WAITING);

        when(resourceRepository.findByIdForUpdate(resource.getId())).thenReturn(Optional.of(resource));
        when(waitlistEntryRepository.findByResourceIdAndStatusOrderByCreatedAtAsc(resource.getId(), WaitlistStatus.WAITING))
                .thenReturn(List.of(entry));
        when(bookingRepository.findOverlapping(eq(resource.getId()), any(), any())).thenReturn(List.of());
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });

        SlotChange slot = new SlotChange(space.getId(), resource.getId(), "Desk 1", at(9), at(10));
        service().promoteForReleasedSlot(slot);

        assertThat(entry.getStatus()).isEqualTo(WaitlistStatus.OFFERED);
        assertThat(entry.getHeldBooking()).isNotNull();
        assertThat(entry.getHeldBooking().getStatus()).isEqualTo(BookingStatus.HELD);
        verify(waitlistEventPublisher).publishOffer(eq(waiter.getId()), any(WaitlistMessage.class));
    }

    @Test
    void promotionWithNoWaitersPublishesAvailable() {
        Space space = space();
        Resource resource = resource(space);
        when(resourceRepository.findByIdForUpdate(resource.getId())).thenReturn(Optional.of(resource));
        when(waitlistEntryRepository.findByResourceIdAndStatusOrderByCreatedAtAsc(resource.getId(), WaitlistStatus.WAITING))
                .thenReturn(List.of());

        SlotChange slot = new SlotChange(space.getId(), resource.getId(), "Desk 1", at(9), at(10));
        service().promoteForReleasedSlot(slot);

        verify(availabilityEventPublisher).publishAvailable(slot);
    }

    @Test
    void claimConfirmsLiveHold() {
        Space space = space();
        Resource resource = resource(space);
        User waiter = new User("w@tempo.africa", "h", "F", "L", Role.MEMBER);
        waiter.setId(UUID.randomUUID());
        Booking held = new Booking(resource, waiter, space, TimeRangeUtil.toRange(at(9), at(10)), BookingStatus.HELD);
        held.setId(UUID.randomUUID());
        held.setHoldExpiresAt(Instant.now().plusSeconds(120));
        WaitlistEntry entry = new WaitlistEntry(resource, space, waiter,
                TimeRangeUtil.toRange(at(9), at(10)), WaitlistStatus.OFFERED);
        entry.setHeldBooking(held);

        when(waitlistEntryRepository.findById(any())).thenReturn(Optional.of(entry));
        when(bookingRepository.findByIdForUpdate(held.getId())).thenReturn(Optional.of(held));

        BookingResponse response = service().claim(waiter.getId(), entry.getId());

        assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(held.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(entry.getStatus()).isEqualTo(WaitlistStatus.FULFILLED);
        verify(waitlistEventPublisher).publishGranted(eq(waiter.getId()), any(WaitlistMessage.class));
    }

    @Test
    void claimOnExpiredHoldIsRejected() {
        Space space = space();
        Resource resource = resource(space);
        User waiter = new User("w@tempo.africa", "h", "F", "L", Role.MEMBER);
        waiter.setId(UUID.randomUUID());
        Booking held = new Booking(resource, waiter, space, TimeRangeUtil.toRange(at(9), at(10)), BookingStatus.HELD);
        held.setId(UUID.randomUUID());
        held.setHoldExpiresAt(Instant.now().minusSeconds(1));
        WaitlistEntry entry = new WaitlistEntry(resource, space, waiter,
                TimeRangeUtil.toRange(at(9), at(10)), WaitlistStatus.OFFERED);
        entry.setHeldBooking(held);

        when(waitlistEntryRepository.findById(any())).thenReturn(Optional.of(entry));
        when(bookingRepository.findByIdForUpdate(held.getId())).thenReturn(Optional.of(held));

        assertThatThrownBy(() -> service().claim(waiter.getId(), entry.getId()))
                .isInstanceOf(OfferExpiredException.class);
    }

    @Test
    void expireHoldMarksExpiredAndDelegatesPromotion() {
        Space space = space();
        Resource resource = resource(space);
        User waiter = new User("w@tempo.africa", "h", "F", "L", Role.MEMBER);
        waiter.setId(UUID.randomUUID());
        Booking held = new Booking(resource, waiter, space, TimeRangeUtil.toRange(at(9), at(10)), BookingStatus.HELD);
        held.setId(UUID.randomUUID());
        held.setHoldExpiresAt(Instant.now().minusSeconds(1));
        WaitlistEntry entry = new WaitlistEntry(resource, space, waiter,
                TimeRangeUtil.toRange(at(9), at(10)), WaitlistStatus.OFFERED);
        entry.setHeldBooking(held);

        when(bookingRepository.findByIdForUpdate(held.getId())).thenReturn(Optional.of(held));
        when(waitlistEntryRepository.findByHeldBooking_Id(held.getId())).thenReturn(Optional.of(entry));

        service().expireHold(held.getId());

        assertThat(held.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(entry.getStatus()).isEqualTo(WaitlistStatus.EXPIRED);
        verify(waitlistPromoter).promoteForReleasedSlot(any(SlotChange.class));
    }
}
