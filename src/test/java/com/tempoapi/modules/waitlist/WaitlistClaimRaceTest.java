package com.tempoapi.modules.waitlist;

import com.tempoapi.AbstractIntegrationTest;
import com.tempoapi.entity.BookingStatus;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.entity.WaitlistEntry;
import com.tempoapi.entity.WaitlistStatus;
import com.tempoapi.exception.OfferExpiredException;
import com.tempoapi.modules.bookings.BookingService;
import com.tempoapi.modules.bookings.dto.BookingResponse;
import com.tempoapi.modules.bookings.dto.CreateBookingRequest;
import com.tempoapi.modules.waitlist.dto.JoinWaitlistRequest;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import com.tempoapi.repository.WaitlistEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.waitlist.hold-seconds=3")
class WaitlistClaimRaceTest extends AbstractIntegrationTest {

    @Autowired
    private BookingService bookingService;
    @Autowired
    private WaitlistService waitlistService;
    @Autowired
    private WaitlistScheduler waitlistScheduler;
    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private ResourceRepository resourceRepository;
    @Autowired
    private WaitlistEntryRepository waitlistEntryRepository;

    private OffsetDateTime at(int hour) {
        return LocalDate.now().plusDays(1).atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    private WaitlistEntry offerToWaiter() {
        Space space = spaceRepository.save(
                new Space("Hub", "Accra", "d", LocalTime.of(8, 0), LocalTime.of(20, 0)));
        Resource resource = resourceRepository.save(
                new Resource(space, "Desk 1", ResourceType.DESK, null));
        User a = persistUser(Role.MEMBER);
        User b = persistUser(Role.MEMBER);
        BookingResponse booking = bookingService.createBooking(
                a.getId(), new CreateBookingRequest(resource.getId(), at(9), at(10)));
        waitlistService.join(b.getId(), new JoinWaitlistRequest(resource.getId(), at(9), at(10)));
        bookingService.cancelBooking(a.getId(), booking.id());
        return waitlistEntryRepository.findByUserId(b.getId(), Pageable.unpaged())
                .getContent().get(0);
    }

    @Test
    void sweeperWinsAfterExpiryAndClaimIsRejected() throws InterruptedException {
        WaitlistEntry entry = offerToWaiter();
        Thread.sleep(3100);
        waitlistScheduler.sweepExpiredHolds();

        assertThatThrownBy(() -> waitlistService.claim(entry.getUser().getId(), entry.getId()))
                .isInstanceOf(OfferExpiredException.class);
    }

    @Test
    void claimBeforeExpiryWinsAndSweepIsNoOp() throws InterruptedException {
        WaitlistEntry entry = offerToWaiter();
        BookingResponse confirmed = waitlistService.claim(entry.getUser().getId(), entry.getId());
        assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);

        Thread.sleep(3100);
        waitlistScheduler.sweepExpiredHolds(); // must not touch the now-confirmed booking

        WaitlistEntry reloaded = waitlistEntryRepository.findById(entry.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(WaitlistStatus.FULFILLED);
    }
}
