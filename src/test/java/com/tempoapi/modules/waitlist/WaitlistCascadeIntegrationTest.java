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
import com.tempoapi.modules.bookings.BookingService;
import com.tempoapi.modules.bookings.dto.BookingResponse;
import com.tempoapi.modules.bookings.dto.CreateBookingRequest;
import com.tempoapi.modules.waitlist.dto.JoinWaitlistRequest;
import com.tempoapi.repository.BookingRepository;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import com.tempoapi.repository.WaitlistEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class WaitlistCascadeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BookingService bookingService;
    @Autowired
    private WaitlistService waitlistService;
    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private ResourceRepository resourceRepository;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private WaitlistEntryRepository waitlistEntryRepository;

    private OffsetDateTime at(int hour) {
        return LocalDate.now().plusDays(1).atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    @Test
    void cancellationOffersThenCascadesThenConfirms() {
        Space space = spaceRepository.save(
                new Space("Hub", "Accra", "d", LocalTime.of(8, 0), LocalTime.of(20, 0)));
        Resource resource = resourceRepository.save(
                new Resource(space, "Desk 1", ResourceType.DESK, null));
        User a = persistUser(Role.MEMBER);
        User b = persistUser(Role.MEMBER);
        User c = persistUser(Role.MEMBER);

        BookingResponse booking = bookingService.createBooking(
                a.getId(), new CreateBookingRequest(resource.getId(), at(9), at(10)));
        waitlistService.join(b.getId(), new JoinWaitlistRequest(resource.getId(), at(9), at(10)));
        waitlistService.join(c.getId(), new JoinWaitlistRequest(resource.getId(), at(9), at(10)));

        bookingService.cancelBooking(a.getId(), booking.id());

        WaitlistEntry bEntry = waitlistEntryRepository
                .findByUserId(b.getId(), org.springframework.data.domain.Pageable.unpaged())
                .getContent().get(0);
        assertThat(bEntry.getStatus()).isEqualTo(WaitlistStatus.OFFERED);
        java.util.UUID heldId = bEntry.getHeldBooking().getId();
        assertThat(bookingRepository.findById(heldId).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.HELD);

        waitlistService.leaveOrDecline(b.getId(), bEntry.getId());

        WaitlistEntry cEntry = waitlistEntryRepository
                .findByUserId(c.getId(), org.springframework.data.domain.Pageable.unpaged())
                .getContent().get(0);
        assertThat(cEntry.getStatus()).isEqualTo(WaitlistStatus.OFFERED);

        BookingResponse confirmed = waitlistService.claim(c.getId(), cEntry.getId());
        assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);

        long confirmedCount = bookingRepository.countByResourceIdAndStatus(
                resource.getId(), BookingStatus.CONFIRMED);
        assertThat(confirmedCount).isEqualTo(1);
    }
}
