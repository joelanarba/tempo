package com.tempoapi.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Fallback publisher; the WebSocket implementation supersedes it via {@code @Primary}. */
@Component
public class LoggingWaitlistPublisher implements WaitlistEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingWaitlistPublisher.class);

    @Override
    public void publishOffer(UUID userId, WaitlistMessage message) {
        log.info("Waitlist OFFER user={} resource={} {}..{} expires={}",
                userId, message.resourceId(), message.startTime(), message.endTime(), message.expiresAt());
    }

    @Override
    public void publishGranted(UUID userId, WaitlistMessage message) {
        log.info("Waitlist GRANTED user={} resource={} {}..{}",
                userId, message.resourceId(), message.startTime(), message.endTime());
    }
}
