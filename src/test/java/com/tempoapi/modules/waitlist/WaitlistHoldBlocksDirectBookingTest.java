package com.tempoapi.modules.waitlist;

import com.tempoapi.AbstractIntegrationTest;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.exception.SlotUnavailableException;
import com.tempoapi.modules.bookings.BookingService;
import com.tempoapi.modules.bookings.dto.BookingResponse;
import com.tempoapi.modules.bookings.dto.CreateBookingRequest;
import com.tempoapi.modules.waitlist.dto.JoinWaitlistRequest;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that a HELD booking (created when a waitlisted member is offered a freed slot)
 * blocks a direct booking attempt for the same resource and time slot.
 *
 * <p>The BookingService's findOverlapping query counts HELD bookings as occupied, so a third
 * member cannot steal a held slot from a waiter who has not yet claimed or declined.
 *
 * <p>Testcontainers (Codespaces only — Docker unavailable locally).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class WaitlistHoldBlocksDirectBookingTest extends AbstractIntegrationTest {

    @Autowired
    private BookingService bookingService;
    @Autowired
    private WaitlistService waitlistService;
    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private ResourceRepository resourceRepository;

    private OffsetDateTime at(int hour) {
        return LocalDate.now().plusDays(1).atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    @Test
    void directBookingIsRejectedWhenSlotIsHeldForWaiter() {
        Space space = spaceRepository.save(
                new Space("Hub", "Accra", "d", LocalTime.of(8, 0), LocalTime.of(20, 0)));
        Resource resource = resourceRepository.save(
                new Resource(space, "Desk 1", ResourceType.DESK, null));

        User a = persistUser(Role.MEMBER);
        User b = persistUser(Role.MEMBER);
        User d = persistUser(Role.MEMBER);

        // A books the slot; B joins the waitlist.
        BookingResponse booking = bookingService.createBooking(
                a.getId(), new CreateBookingRequest(resource.getId(), at(9), at(10)));
        waitlistService.join(b.getId(), new JoinWaitlistRequest(resource.getId(), at(9), at(10)));

        // A cancels — B is now OFFERED and a HELD booking exists for the slot.
        bookingService.cancelBooking(a.getId(), booking.id());

        // D attempts a direct booking of the same slot while B's hold is live.
        // findOverlapping counts HELD, so this must throw SlotUnavailableException.
        assertThatThrownBy(() -> bookingService.createBooking(
                d.getId(), new CreateBookingRequest(resource.getId(), at(9), at(10))))
                .isInstanceOf(SlotUnavailableException.class);
    }
}
