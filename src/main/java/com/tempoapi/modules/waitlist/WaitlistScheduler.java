package com.tempoapi.modules.waitlist;

import com.tempoapi.entity.Booking;
import com.tempoapi.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Clock;
import java.util.List;

/**
 * Expires each hold in a separate transaction. A failed expiry remains eligible for the next sweep.
 */
@Component
public class WaitlistScheduler {

    private static final Logger log = LoggerFactory.getLogger(WaitlistScheduler.class);
    private static final int BATCH = 100;

    private final BookingRepository bookingRepository;
    private final WaitlistService waitlistService;
    private final Clock clock;

    public WaitlistScheduler(BookingRepository bookingRepository, WaitlistService waitlistService, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.waitlistService = waitlistService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.waitlist.sweep-ms:10000}")
    public void sweepExpiredHolds() {
        List<Booking> expired = bookingRepository.findExpiredHolds(
                OffsetDateTime.now(clock), PageRequest.of(0, BATCH));
        for (Booking hold : expired) {
            try {
                waitlistService.expireHold(hold.getId());
            } catch (RuntimeException ex) {
                log.warn("Failed to expire hold {} ({})", hold.getId(), ex.getClass().getSimpleName());
            }
        }
    }
}
