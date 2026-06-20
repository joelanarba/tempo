package com.tempoapi.websocket;

/**
 * Publishes availability changes to interested clients. The booking service depends
 * on this interface, never on a concrete transport, so the realtime mechanism can be
 * swapped (logging in Sprint 3, WebSocket in Sprint 4) without touching booking logic.
 */
public interface AvailabilityEventPublisher {

    /** Signals that a slot has just become booked. */
    void publishBooked(SlotChange slot);

    /** Signals that a slot has just become available again. */
    void publishAvailable(SlotChange slot);
}
