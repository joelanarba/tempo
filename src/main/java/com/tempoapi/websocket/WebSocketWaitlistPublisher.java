package com.tempoapi.websocket;

import org.springframework.context.annotation.Primary;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Pushes private waitlist events to {@code /user/queue/waitlist}. Spring resolves the
 * user destination from the STOMP session principal (the user id set by the CONNECT
 * interceptor), so each member only receives their own offers.
 */
@Component
@Primary
public class WebSocketWaitlistPublisher implements WaitlistEventPublisher {

    private static final String DESTINATION = "/queue/waitlist";

    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketWaitlistPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void publishOffer(UUID userId, WaitlistMessage message) {
        messagingTemplate.convertAndSendToUser(userId.toString(), DESTINATION, message);
    }

    @Override
    public void publishGranted(UUID userId, WaitlistMessage message) {
        messagingTemplate.convertAndSendToUser(userId.toString(), DESTINATION, message);
    }
}
