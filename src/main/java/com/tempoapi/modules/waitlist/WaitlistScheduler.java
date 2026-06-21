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
import java.util.List;

/**
 * Periodically expires holds whose offer window has lapsed and cascades each freed slot to the
 * next waiter. Single-instance: with multiple app nodes, wrap the sweep in a distributed lock
 * (for example ShedLock) so only one node promotes.
 */
@Component
public class WaitlistScheduler {

    private static final Logger log = LoggerFactory.getLogger(WaitlistScheduler.class);
    private static final int BATCH = 100;

    private final BookingRepository bookingRepository;
    private final WaitlistService waitlistService;

    public WaitlistScheduler(BookingRepository bookingRepository, WaitlistService waitlistService) {
        this.bookingRepository = bookingRepository;
        this.waitlistService = waitlistService;
    }

    @Scheduled(fixedDelayString = "${app.waitlist.sweep-ms:10000}")
    public void sweepExpiredHolds() {
        List<Booking> expired = bookingRepository.findExpiredHolds(
                OffsetDateTime.now(ZoneOffset.UTC), PageRequest.of(0, BATCH));
        for (Booking hold : expired) {
            try {
                waitlistService.expireHold(hold.getId());
            } catch (RuntimeException ex) {
                log.warn("Failed to expire hold {}: {}", hold.getId(), ex.getMessage());
            }
        }
    }
}
