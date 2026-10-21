package com.tempoapi.modules.bookings;

import com.tempoapi.AbstractIntegrationTest;
import com.tempoapi.entity.*;
import com.tempoapi.modules.bookings.dto.CreateBookingRequest;
import com.tempoapi.modules.waitlist.WaitlistService;
import com.tempoapi.modules.waitlist.dto.JoinWaitlistRequest;
import com.tempoapi.repository.*;
import com.tempoapi.websocket.WebSocketAvailabilityPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class BookingTransactionTest extends AbstractIntegrationTest {
    @Autowired BookingService bookings;
    @Autowired WaitlistService waitlist;
    @Autowired SpaceRepository spaces;
    @Autowired ResourceRepository resources;
    @Autowired WaitlistEntryRepository entries;
    @Autowired PlatformTransactionManager transactionManager;
    @SpyBean BookingRepository bookingRepository;
    @SpyBean WebSocketAvailabilityPublisher publisher;

    private Resource resource() {
        Space space = spaces.save(new Space("Hub", "UTC", "", LocalTime.of(8, 0), LocalTime.of(20, 0)));
        return resources.save(new Resource(space, "Desk", ResourceType.DESK, null));
    }

    private OffsetDateTime at(int hour) {
        return LocalDate.now(ZoneOffset.UTC).plusDays(1).atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    @Test
    void rollbackCreatesNeitherBookingNorAvailabilityEvent() {
        Resource resource = resource();
        User owner = persistUser(Role.MEMBER);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            bookings.createBooking(owner.getId(), new CreateBookingRequest(resource.getId(), at(9), at(10)));
            verify(publisher, never()).publishBooked(argThat(slot -> slot.resourceId().equals(resource.getId())));
            status.setRollbackOnly();
        });
        assertThat(bookingRepository.countByResourceIdAndStatus(resource.getId(), BookingStatus.CONFIRMED)).isZero();
        verify(publisher, never()).publishBooked(argThat(slot -> slot.resourceId().equals(resource.getId())));
    }

    @Test
    void failedPromotionRollsBackCancellationAndQueueChange() {
        Resource resource = resource();
        User owner = persistUser(Role.MEMBER);
        User waiter = persistUser(Role.MEMBER);
        var booking = bookings.createBooking(owner.getId(), new CreateBookingRequest(resource.getId(), at(9), at(10)));
        var entry = waitlist.join(waiter.getId(), new JoinWaitlistRequest(resource.getId(), at(9), at(10)));
        doThrow(new DataIntegrityViolationException("forced hold insert failure")).when(bookingRepository)
                .saveAndFlush(argThat(candidate -> candidate.getStatus() == BookingStatus.HELD));
        assertThatThrownBy(() -> bookings.cancelBooking(owner.getId(), booking.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(bookingRepository.findById(booking.id()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(entries.findById(entry.id()).orElseThrow().getStatus()).isEqualTo(WaitlistStatus.WAITING);
        assertThat(bookingRepository.countByResourceIdAndStatus(resource.getId(), BookingStatus.HELD)).isZero();
        verify(publisher, never()).publishAvailable(argThat(slot -> slot.resourceId().equals(resource.getId())));
    }

    @Test
    void notificationFailurePreservesSuccessfulBookingResponseAndCommit() {
        Resource resource = resource();
        User owner = persistUser(Role.MEMBER);
        doThrow(new IllegalStateException("transport unavailable")).when(publisher).publishBooked(any());
        var booking = bookings.createBooking(owner.getId(), new CreateBookingRequest(resource.getId(), at(9), at(10)));
        assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(bookingRepository.findById(booking.id()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void releaseDoesNotPromoteUnrelatedInterval() {
        Resource resource = resource();
        User owner = persistUser(Role.MEMBER);
        User unrelated = persistUser(Role.MEMBER);
        var booking = bookings.createBooking(owner.getId(), new CreateBookingRequest(resource.getId(), at(9), at(10)));
        WaitlistEntry entry = entries.saveAndFlush(new WaitlistEntry(resource, resource.getSpace(), unrelated,
                com.tempoapi.util.TimeRangeUtil.toRange(at(9).plusDays(1), at(10).plusDays(1)), WaitlistStatus.WAITING));
        bookings.cancelBooking(owner.getId(), booking.id());
        assertThat(entries.findById(entry.getId()).orElseThrow().getStatus()).isEqualTo(WaitlistStatus.WAITING);
        assertThat(bookingRepository.countByResourceIdAndStatus(resource.getId(), BookingStatus.HELD)).isZero();
        verify(publisher).publishAvailable(argThat(slot -> slot.resourceId().equals(resource.getId())
                && slot.startTime().isEqual(at(9))));
    }
}
