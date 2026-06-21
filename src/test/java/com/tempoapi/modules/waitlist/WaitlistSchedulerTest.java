package com.tempoapi.modules.waitlist;

import com.tempoapi.entity.Booking;
import com.tempoapi.repository.BookingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaitlistSchedulerTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private WaitlistService waitlistService;

    @Test
    void sweepExpiresEachStaleHold() {
        Booking hold = new Booking();
        hold.setId(UUID.randomUUID());
        when(bookingRepository.findExpiredHolds(any(OffsetDateTime.class), any()))
                .thenReturn(List.of(hold));

        new WaitlistScheduler(bookingRepository, waitlistService).sweepExpiredHolds();

        verify(waitlistService).expireHold(hold.getId());
    }
}
