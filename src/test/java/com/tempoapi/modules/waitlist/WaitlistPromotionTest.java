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
import com.tempoapi.modules.availability.AvailabilityChanges;
import java.time.Clock;
import com.tempoapi.modules.bookings.dto.BookingResponse;
import com.tempoapi.repository.BookingRepository;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.UserRepository;
import com.tempoapi.repository.WaitlistEntryRepository;
import com.tempoapi.util.TimeRangeUtil;
import com.tempoapi.websocket.SlotChange;
import com.tempoapi.websocket.WaitlistEventPublisher;
import com.tempoapi.websocket.WaitlistMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaitlistPromotionTest {

    @Mock private WaitlistEntryRepository waitlistEntryRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private ResourceRepository resourceRepository;
    @Mock private UserRepository userRepository;
    @Mock private AvailabilityChanges availabilityChanges;
    @Mock private WaitlistEventPublisher waitlistEventPublisher;

    private WaitlistService service() {
        return service(Clock.systemUTC());
    }

    private WaitlistService service(Clock clock) {
        return new WaitlistService(waitlistEntryRepository, bookingRepository, resourceRepository,
                userRepository, availabilityChanges, waitlistEventPublisher, 120, clock);
    }

    private void stubEntry(WaitlistEntry entry, Resource resource) {
        org.springframework.test.util.ReflectionTestUtils.setField(entry, "id", UUID.randomUUID());
        when(waitlistEntryRepository.findResourceIdById(entry.getId())).thenReturn(Optional.of(resource.getId()));
        when(resourceRepository.findByIdForUpdate(resource.getId())).thenReturn(Optional.of(resource));
        when(waitlistEntryRepository.findByIdForUpdate(entry.getId())).thenReturn(Optional.of(entry));
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
        when(waitlistEntryRepository.findWaitingForSlot(eq(resource.getId()), any(), any()))
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
        when(waitlistEntryRepository.findWaitingForSlot(eq(resource.getId()), any(), any()))
                .thenReturn(List.of());

        SlotChange slot = new SlotChange(space.getId(), resource.getId(), "Desk 1", at(9), at(10));
        service().promoteForReleasedSlot(slot);

        verify(availabilityChanges).available(slot);
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

        stubEntry(entry, resource);
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

        stubEntry(entry, resource);
        when(bookingRepository.findByIdForUpdate(held.getId())).thenReturn(Optional.of(held));

        assertThatThrownBy(() -> service().claim(waiter.getId(), entry.getId()))
                .isInstanceOf(OfferExpiredException.class);
    }

    @Test
    void expireHoldMarksExpiredAndPublishesRelease() {
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

        when(bookingRepository.findResourceIdById(held.getId())).thenReturn(Optional.of(resource.getId()));
        when(resourceRepository.findByIdForUpdate(resource.getId())).thenReturn(Optional.of(resource));
        when(bookingRepository.findByIdForUpdate(held.getId())).thenReturn(Optional.of(held));
        when(waitlistEntryRepository.findByHeldBooking_Id(held.getId())).thenReturn(Optional.of(entry));

        service().expireHold(held.getId());

        assertThat(held.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(entry.getStatus()).isEqualTo(WaitlistStatus.EXPIRED);
        verify(availabilityChanges).available(any(SlotChange.class));
    }

    private void makeIneligible(String reason, Resource resource, User user) {
        switch (reason) {
            case "resource" -> resource.setActive(false);
            case "space" -> resource.getSpace().setActive(false);
            case "user" -> user.setActive(false);
            case "hours" -> resource.getSpace().setClosingTime(LocalTime.of(8, 30));
            case "started" -> { }
            default -> throw new IllegalArgumentException(reason);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"resource", "space", "user", "hours", "started"})
    void promotionExpiresIneligibleEntriesWithoutCreatingAHold(String reason) {
        Space space = space();
        Resource resource = resource(space);
        User user = new User("waiter@tempo.test", "hash", "Test", "Member", Role.MEMBER);
        WaitlistEntry entry = new WaitlistEntry(resource, space, user,
                TimeRangeUtil.toRange(at(9), at(10)), WaitlistStatus.WAITING);
        makeIneligible(reason, resource, user);
        Clock clock = Clock.fixed((reason.equals("started") ? at(9) : at(8)).toInstant(), ZoneOffset.UTC);
        when(resourceRepository.findByIdForUpdate(resource.getId())).thenReturn(Optional.of(resource));
        when(waitlistEntryRepository.findWaitingForSlot(eq(resource.getId()), any(), any())).thenReturn(List.of(entry));
        SlotChange slot = new SlotChange(space.getId(), resource.getId(), resource.getName(), at(9), at(10));

        service(clock).promoteForReleasedSlot(slot);

        assertThat(entry.getStatus()).isEqualTo(WaitlistStatus.EXPIRED);
        verify(bookingRepository, never()).saveAndFlush(any());
        verify(availabilityChanges).available(slot);
    }

    @ParameterizedTest
    @ValueSource(strings = {"resource", "space", "user", "hours", "started"})
    void claimRechecksEligibilityBeforeConfirming(String reason) {
        Space space = space();
        Resource resource = resource(space);
        User user = new User("waiter@tempo.test", "hash", "Test", "Member", Role.MEMBER);
        user.setId(UUID.randomUUID());
        Booking hold = new Booking(resource, user, space, TimeRangeUtil.toRange(at(9), at(10)), BookingStatus.HELD);
        hold.setId(UUID.randomUUID());
        hold.setHoldExpiresAt(at(11).toInstant());
        WaitlistEntry entry = new WaitlistEntry(resource, space, user, hold.getTimeRange(), WaitlistStatus.OFFERED);
        entry.setHeldBooking(hold);
        makeIneligible(reason, resource, user);
        stubEntry(entry, resource);
        when(bookingRepository.findByIdForUpdate(hold.getId())).thenReturn(Optional.of(hold));
        Clock clock = Clock.fixed((reason.equals("started") ? at(9) : at(8)).toInstant(), ZoneOffset.UTC);

        assertThatThrownBy(() -> service(clock).claim(user.getId(), entry.getId())).isInstanceOf(OfferExpiredException.class);

        assertThat(hold.getStatus()).isEqualTo(BookingStatus.HELD);
        assertThat(entry.getStatus()).isEqualTo(WaitlistStatus.OFFERED);
        verify(waitlistEventPublisher, never()).publishGranted(any(), any());
    }

    @Test
    void offerDeadlineCannotExtendBeyondSlotStart() {
        Space space = space();
        Resource resource = resource(space);
        User user = new User("waiter@tempo.test", "hash", "Test", "Member", Role.MEMBER);
        user.setId(UUID.randomUUID());
        WaitlistEntry entry = new WaitlistEntry(resource, space, user,
                TimeRangeUtil.toRange(at(9), at(10)), WaitlistStatus.WAITING);
        when(resourceRepository.findByIdForUpdate(resource.getId())).thenReturn(Optional.of(resource));
        when(waitlistEntryRepository.findWaitingForSlot(eq(resource.getId()), any(), any())).thenReturn(List.of(entry));
        when(bookingRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Clock clock = Clock.fixed(at(9).minusSeconds(30).toInstant(), ZoneOffset.UTC);

        service(clock).promoteForReleasedSlot(new SlotChange(space.getId(), resource.getId(), resource.getName(), at(9), at(10)));

        assertThat(entry.getHeldBooking().getHoldExpiresAt()).isEqualTo(at(9).toInstant());
    }
}
