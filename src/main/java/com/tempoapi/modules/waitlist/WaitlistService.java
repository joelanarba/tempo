package com.tempoapi.modules.waitlist;

import com.tempoapi.common.PageResponse;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.entity.WaitlistEntry;
import com.tempoapi.entity.WaitlistStatus;
import com.tempoapi.exception.ForbiddenException;
import com.tempoapi.exception.ResourceNotFoundException;
import com.tempoapi.exception.SlotAvailableException;
import com.tempoapi.exception.ValidationException;
import com.tempoapi.modules.availability.AvailabilityCache;
import com.tempoapi.modules.waitlist.dto.JoinWaitlistRequest;
import com.tempoapi.modules.waitlist.dto.WaitlistEntryResponse;
import com.tempoapi.repository.BookingRepository;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.UserRepository;
import com.tempoapi.repository.WaitlistEntryRepository;
import com.tempoapi.util.TimeRangeUtil;
import com.tempoapi.websocket.AvailabilityEventPublisher;
import com.tempoapi.websocket.SlotChange;
import com.tempoapi.websocket.WaitlistEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class WaitlistService implements WaitlistPromoter {

    private final WaitlistEntryRepository waitlistEntryRepository;
    private final BookingRepository bookingRepository;
    private final ResourceRepository resourceRepository;
    private final UserRepository userRepository;
    private final AvailabilityCache availabilityCache;
    private final AvailabilityEventPublisher availabilityEventPublisher;
    private final WaitlistEventPublisher waitlistEventPublisher;
    private final long holdSeconds;

    public WaitlistService(WaitlistEntryRepository waitlistEntryRepository,
                           BookingRepository bookingRepository,
                           ResourceRepository resourceRepository,
                           UserRepository userRepository,
                           AvailabilityCache availabilityCache,
                           AvailabilityEventPublisher availabilityEventPublisher,
                           WaitlistEventPublisher waitlistEventPublisher,
                           @Value("${app.waitlist.hold-seconds:120}") long holdSeconds) {
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.bookingRepository = bookingRepository;
        this.resourceRepository = resourceRepository;
        this.userRepository = userRepository;
        this.availabilityCache = availabilityCache;
        this.availabilityEventPublisher = availabilityEventPublisher;
        this.waitlistEventPublisher = waitlistEventPublisher;
        this.holdSeconds = holdSeconds;
    }

    /** Joins the FIFO waitlist for a currently-taken resource and slot. */
    @Transactional
    public WaitlistEntryResponse join(UUID userId, JoinWaitlistRequest request) {
        validateTimes(request.startTime(), request.endTime());

        Resource resource = resourceRepository.findById(request.resourceId())
                .orElseThrow(() -> new ResourceNotFoundException("Resource not found"));
        if (!resource.isActive() || !resource.getSpace().isActive()) {
            throw new ResourceNotFoundException("Resource is not available");
        }
        Space space = resource.getSpace();
        validateWithinOpeningHours(space, request.startTime(), request.endTime());

        OffsetDateTime start = TimeRangeUtil.toUtc(request.startTime());
        OffsetDateTime end = TimeRangeUtil.toUtc(request.endTime());

        if (bookingRepository.findOverlapping(resource.getId(), start, end).isEmpty()) {
            throw new SlotAvailableException("The slot is available; book it directly instead of waitlisting");
        }
        if (waitlistEntryRepository.existsActiveEntry(resource.getId(), userId, start, end)) {
            throw new com.tempoapi.exception.AlreadyWaitlistedException("Already on the waitlist for this slot");
        }

        User user = userRepository.getReferenceById(userId);
        WaitlistEntry entry = new WaitlistEntry(resource, space, user,
                TimeRangeUtil.toRange(request.startTime(), request.endTime()), WaitlistStatus.WAITING);
        try {
            entry = waitlistEntryRepository.save(entry);
        } catch (DataIntegrityViolationException ex) {
            throw new com.tempoapi.exception.AlreadyWaitlistedException("Already on the waitlist for this slot");
        }
        return WaitlistEntryResponse.from(entry, positionOf(entry));
    }

    /** Lists the caller's waitlist entries. */
    @Transactional(readOnly = true)
    public PageResponse<WaitlistEntryResponse> listMine(UUID userId, Pageable pageable) {
        return PageResponse.from(waitlistEntryRepository.findByUserId(userId, pageable)
                .map(entry -> WaitlistEntryResponse.from(entry, null)));
    }

    /** Leaves a WAITING line, or declines an OFFERED hold (cascading to the next waiter). */
    @Transactional
    public void leaveOrDecline(UUID userId, UUID entryId) {
        WaitlistEntry entry = waitlistEntryRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Waitlist entry not found"));
        if (!entry.getUser().getId().equals(userId)) {
            throw new ForbiddenException("You can only manage your own waitlist entry");
        }
        if (entry.getStatus() == WaitlistStatus.WAITING) {
            entry.setStatus(WaitlistStatus.CANCELLED);
            return;
        }
        if (entry.getStatus() == WaitlistStatus.OFFERED) {
            declineOffer(entry);
            return;
        }
        throw new ValidationException("Waitlist entry is not active");
    }

    // ---- Filled in Task 6 ----

    @Override
    @Transactional
    public void promoteForReleasedSlot(SlotChange releasedSlot) {
        // Implemented in Task 6.
    }

    private void declineOffer(WaitlistEntry entry) {
        // Implemented in Task 6.
    }

    private int positionOf(WaitlistEntry entry) {
        List<WaitlistEntry> waiting = waitlistEntryRepository
                .findByResourceIdAndStatusOrderByCreatedAtAsc(entry.getResource().getId(), WaitlistStatus.WAITING);
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).getId() != null && waiting.get(i).getId().equals(entry.getId())) {
                return i + 1;
            }
        }
        return waiting.size() + 1;
    }

    private void validateTimes(OffsetDateTime startTime, OffsetDateTime endTime) {
        if (!startTime.isBefore(endTime)) {
            throw new ValidationException("startTime must be before endTime");
        }
        if (!startTime.isAfter(OffsetDateTime.now())) {
            throw new ValidationException("startTime must be in the future");
        }
    }

    private void validateWithinOpeningHours(Space space, OffsetDateTime startTime, OffsetDateTime endTime) {
        if (!startTime.toLocalDate().equals(endTime.toLocalDate())) {
            throw new ValidationException("A booking must start and end on the same day");
        }
        if (startTime.toLocalTime().isBefore(space.getOpeningTime())) {
            throw new ValidationException("Slot starts before the space opening time");
        }
        if (endTime.toLocalTime().isAfter(space.getClosingTime())) {
            throw new ValidationException("Slot ends after the space closing time");
        }
    }
}
