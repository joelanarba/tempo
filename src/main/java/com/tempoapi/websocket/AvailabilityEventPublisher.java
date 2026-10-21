package com.tempoapi.websocket;

/**
 * Publishes availability changes to interested clients. The booking service depends
 * on this interface so transport failures remain separate from booking decisions.
 */
public interface AvailabilityEventPublisher {

    /** Signals that a slot has just become booked. */
    void publishBooked(SlotChange slot);

    /** Signals that a slot has just become available again. */
    void publishAvailable(SlotChange slot);

    void publishChanged(java.util.UUID spaceId);
}
