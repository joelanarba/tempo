package com.tempoapi.modules.availability;

import com.tempoapi.exception.ResourceNotFoundException;
import com.tempoapi.repository.SpaceRepository;
import com.tempoapi.util.AfterCommit;
import com.tempoapi.util.TimeRangeUtil;
import com.tempoapi.websocket.AvailabilityEventPublisher;
import com.tempoapi.websocket.SlotChange;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class AvailabilityChanges {
    private final SpaceRepository spaceRepository;
    private final AvailabilityEventPublisher publisher;

    public AvailabilityChanges(SpaceRepository spaceRepository, AvailabilityEventPublisher publisher) {
        this.spaceRepository = spaceRepository;
        this.publisher = publisher;
    }

    public void booked(SlotChange slot) {
        // The guarded update also serializes eligibility against concurrent space edits.
        int updated = spaceRepository.incrementRevisionIfBookable(slot.spaceId(),
                TimeRangeUtil.toUtc(slot.startTime()).toLocalTime(),
                TimeRangeUtil.toUtc(slot.endTime()).toLocalTime());
        if (updated != 1) {
            throw new ResourceNotFoundException("Space is not available for this slot");
        }
        AfterCommit.run(() -> publisher.publishBooked(slot));
    }

    public void available(SlotChange slot) {
        spaceRepository.incrementAvailabilityRevision(slot.spaceId());
        AfterCommit.run(() -> publisher.publishAvailable(slot));
    }

    public void changed(UUID spaceId) {
        spaceRepository.incrementAvailabilityRevision(spaceId);
        AfterCommit.run(() -> publisher.publishChanged(spaceId));
    }
}
