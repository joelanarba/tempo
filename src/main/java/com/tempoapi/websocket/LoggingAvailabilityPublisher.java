package com.tempoapi.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Placeholder publisher used until the WebSocket transport lands in Sprint 4. It
 * proves the booking service is decoupled from the transport: Sprint 4 adds a
 * WebSocket implementation marked {@code @Primary} and this class steps aside with
 * no change to {@code BookingService}.
 */
@Component
public class LoggingAvailabilityPublisher implements AvailabilityEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingAvailabilityPublisher.class);

    @Override
    public void publishBooked(SlotChange slot) {
        log.info("Availability change [BOOKED] space={} resource={} {}..{}",
                slot.spaceId(), slot.resourceId(), slot.startTime(), slot.endTime());
    }

    @Override
    public void publishAvailable(SlotChange slot) {
        log.info("Availability change [AVAILABLE] space={} resource={} {}..{}",
                slot.spaceId(), slot.resourceId(), slot.startTime(), slot.endTime());
    }
}
