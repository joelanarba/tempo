package com.tempoapi.modules.bookings;

import com.tempoapi.AbstractIntegrationTest;
import com.tempoapi.entity.Booking;
import com.tempoapi.entity.BookingStatus;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.exception.SlotUnavailableException;
import com.tempoapi.modules.bookings.dto.BookingResponse;
import com.tempoapi.modules.bookings.dto.CreateBookingRequest;
import com.tempoapi.repository.BookingRepository;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import com.tempoapi.util.TimeRangeUtil;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class BookingConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private BookingService bookingService;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private ResourceRepository resourceRepository;
    @Autowired
    private SpaceRepository spaceRepository;

    private record Fixture(UUID spaceId, UUID resourceId, UUID userId) {
    }

    private Fixture freshFixture() {
        Space space = spaceRepository.save(new Space(
                "Hub", "Accra", "desc", LocalTime.of(8, 0), LocalTime.of(20, 0)));
        Resource resource = resourceRepository.save(new Resource(
                space, "Desk " + UUID.randomUUID(), ResourceType.DESK, null));
        User user = persistUser(Role.MEMBER);
        return new Fixture(space.getId(), resource.getId(), user.getId());
    }

    private OffsetDateTime at(int hour, int minute) {
        return LocalDate.now().plusDays(1).atTime(hour, minute).atOffset(ZoneOffset.UTC);
    }

    @RepeatedTest(5)
    void exactlyOneOfTwoConcurrentBookingsSucceeds() throws Exception {
        Fixture fixture = freshFixture();
        CreateBookingRequest request = new CreateBookingRequest(fixture.resourceId(), at(9, 0), at(10, 0));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Callable<String> attempt = () -> {
            ready.countDown();
            go.await();
            try {
                bookingService.createBooking(fixture.userId(), request);
                return "CONFIRMED";
            } catch (SlotUnavailableException ex) {
                return "CONFLICT";
            }
        };

        Future<String> first = pool.submit(attempt);
        Future<String> second = pool.submit(attempt);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        String firstOutcome = first.get(30, TimeUnit.SECONDS);
        String secondOutcome = second.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(List.of(firstOutcome, secondOutcome))
                .containsExactlyInAnyOrder("CONFIRMED", "CONFLICT");
        assertThat(bookingRepository.countByResourceIdAndStatus(fixture.resourceId(), BookingStatus.CONFIRMED))
                .isEqualTo(1);
    }

    @Test
    void databaseRejectsDirectlyInsertedOverlappingConfirmedBookings() {
        Fixture fixture = freshFixture();
        Resource resource = resourceRepository.findById(fixture.resourceId()).orElseThrow();
        Space space = spaceRepository.findById(fixture.spaceId()).orElseThrow();
        User user = userRepository.findById(fixture.userId()).orElseThrow();

        bookingRepository.saveAndFlush(new Booking(resource, user, space,
                TimeRangeUtil.toRange(at(9, 0), at(10, 0)), BookingStatus.CONFIRMED));

        // Bypass the service lock entirely; the GiST exclusion constraint must still reject this.
        assertThatThrownBy(() -> bookingRepository.saveAndFlush(new Booking(resource, user, space,
                TimeRangeUtil.toRange(at(9, 30), at(10, 30)), BookingStatus.CONFIRMED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void adjacentTouchingSlotsAreBothAllowed() {
        Fixture fixture = freshFixture();

        bookingService.createBooking(fixture.userId(),
                new CreateBookingRequest(fixture.resourceId(), at(9, 0), at(10, 0)));
        bookingService.createBooking(fixture.userId(),
                new CreateBookingRequest(fixture.resourceId(), at(10, 0), at(11, 0)));

        assertThat(bookingRepository.countByResourceIdAndStatus(fixture.resourceId(), BookingStatus.CONFIRMED))
                .isEqualTo(2);
    }

    @Test
    void overlappingSlotIsRejected() {
        Fixture fixture = freshFixture();
        bookingService.createBooking(fixture.userId(),
                new CreateBookingRequest(fixture.resourceId(), at(9, 0), at(11, 0)));

        assertThatThrownBy(() -> bookingService.createBooking(fixture.userId(),
                new CreateBookingRequest(fixture.resourceId(), at(10, 0), at(12, 0))))
                .isInstanceOf(SlotUnavailableException.class);
    }

    @Test
    void cancellingFreesTheSlotForRebooking() {
        Fixture fixture = freshFixture();
        BookingResponse booking = bookingService.createBooking(fixture.userId(),
                new CreateBookingRequest(fixture.resourceId(), at(9, 0), at(10, 0)));

        bookingService.cancelBooking(fixture.userId(), booking.id());

        BookingResponse rebooked = bookingService.createBooking(fixture.userId(),
                new CreateBookingRequest(fixture.resourceId(), at(9, 0), at(10, 0)));

        assertThat(rebooked.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(bookingRepository.countByResourceIdAndStatus(fixture.resourceId(), BookingStatus.CONFIRMED))
                .isEqualTo(1);
    }
}
