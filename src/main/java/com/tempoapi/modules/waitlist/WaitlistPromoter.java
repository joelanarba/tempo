package com.tempoapi.modules.waitlist;

import com.tempoapi.websocket.SlotChange;

/**
 * Entry point the booking service calls in its release transaction, without depending on the
 * concrete waitlist service. The implementation either offers the slot to the next eligible
 * waiter (creating a hold) or publishes it as available.
 */
public interface WaitlistPromoter {

    void promoteForReleasedSlot(SlotChange releasedSlot);
}
