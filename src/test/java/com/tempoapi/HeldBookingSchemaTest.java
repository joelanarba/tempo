package com.tempoapi;

import com.tempoapi.entity.Booking;
import com.tempoapi.entity.BookingStatus;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.ResourceType;
import com.tempoapi.entity.Role;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.repository.BookingRepository;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import com.tempoapi.util.TimeRangeUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class HeldBookingSchemaTest extends AbstractIntegrationTest {

    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private ResourceRepository resourceRepository;
    @Autowired
    private BookingRepository bookingRepository;

    private OffsetDateTime at(int hour) {
        return LocalDate.now().plusDays(1).atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }

    @Test
    void heldRowBlocksAnOverlappingConfirmedInsert() {
        Space space = spaceRepository.save(new Space("Hub", "Accra", "d",
                java.time.LocalTime.of(8, 0), java.time.LocalTime.of(20, 0)));
        Resource resource = resourceRepository.save(new Resource(space, "Desk 1", ResourceType.DESK, null));
        User user = persistUser(Role.MEMBER);

        Booking held = new Booking(resource, user, space, TimeRangeUtil.toRange(at(9), at(10)), BookingStatus.HELD);
        held.setHoldExpiresAt(Instant.now().plusSeconds(120));
        bookingRepository.saveAndFlush(held);

        Booking conflict = new Booking(resource, user, space,
                TimeRangeUtil.toRange(at(9), at(10)), BookingStatus.CONFIRMED);

        assertThatThrownBy(() -> bookingRepository.saveAndFlush(conflict))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findOverlappingCountsHeldRows() {
        Space space = spaceRepository.save(new Space("Hub", "Accra", "d",
                java.time.LocalTime.of(8, 0), java.time.LocalTime.of(20, 0)));
        Resource resource = resourceRepository.save(new Resource(space, "Desk 2", ResourceType.DESK, null));
        User user = persistUser(Role.MEMBER);

        Booking held = new Booking(resource, user, space, TimeRangeUtil.toRange(at(9), at(10)), BookingStatus.HELD);
        held.setHoldExpiresAt(Instant.now().plusSeconds(120));
        bookingRepository.saveAndFlush(held);

        assertThat(bookingRepository.findOverlapping(resource.getId(),
                TimeRangeUtil.toUtc(at(9)), TimeRangeUtil.toUtc(at(10)))).isNotEmpty();
    }
}
