package com.tempoapi.websocket;

import java.util.UUID;

/**
 * Publishes private waitlist events to a single member. The waitlist service depends on
 * this interface, never on a concrete transport, mirroring {@link AvailabilityEventPublisher}.
 */
public interface WaitlistEventPublisher {

    /** Notifies a member that a freed slot is being offered to them. */
    void publishOffer(UUID userId, WaitlistMessage message);

    /** Notifies a member that their waitlisted slot is now confirmed. */
    void publishGranted(UUID userId, WaitlistMessage message);
}
