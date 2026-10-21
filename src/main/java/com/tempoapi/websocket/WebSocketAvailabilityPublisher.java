package com.tempoapi.websocket;

import org.springframework.context.annotation.Primary;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Real-time implementation of {@link AvailabilityEventPublisher}. Pushes availability
 * changes to the space topic over STOMP.
 */
@Component
@Primary
public class WebSocketAvailabilityPublisher implements AvailabilityEventPublisher {

    private static final String TOPIC_TEMPLATE = "/topic/spaces/%s/availability";

    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketAvailabilityPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void publishBooked(SlotChange slot) {
        send(slot, AvailabilityState.BOOKED);
    }

    @Override
    public void publishAvailable(SlotChange slot) {
        send(slot, AvailabilityState.AVAILABLE);
    }

    @Override
    public void publishChanged(java.util.UUID spaceId) {
        messagingTemplate.convertAndSend(String.format(TOPIC_TEMPLATE, spaceId),
                new AvailabilityMessage(null, null, null, null, AvailabilityState.CHANGED));
    }

    private void send(SlotChange slot, AvailabilityState state) {
        String destination = String.format(TOPIC_TEMPLATE, slot.spaceId());
        messagingTemplate.convertAndSend(destination, AvailabilityMessage.of(slot, state));
    }
}
