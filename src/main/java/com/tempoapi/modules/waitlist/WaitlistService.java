package com.tempoapi.modules.waitlist;

import com.tempoapi.common.PageResponse;
import com.tempoapi.entity.Booking;
import com.tempoapi.entity.BookingStatus;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.entity.WaitlistEntry;
import com.tempoapi.entity.WaitlistStatus;
import com.tempoapi.exception.ForbiddenException;
import com.tempoapi.exception.OfferExpiredException;
import com.tempoapi.exception.ResourceNotFoundException;
import com.tempoapi.exception.SlotAvailableException;
import com.tempoapi.exception.ValidationException;
import com.tempoapi.modules.availability.AvailabilityCache;
import com.tempoapi.modules.bookings.dto.BookingResponse;
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
import com.tempoapi.websocket.WaitlistMessage;
import com.tempoapi.websocket.WaitlistState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDate;
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
    private final WaitlistPromoter self;

    public WaitlistService(WaitlistEntryRepository waitlistEntryRepository,
                           BookingRepository bookingRepository,
                           ResourceRepository resourceRepository,
                           UserRepository userRepository,
                           AvailabilityCache availabilityCache,
                           AvailabilityEventPublisher availabilityEventPublisher,
                           WaitlistEventPublisher waitlistEventPublisher,
                           @Value("${app.waitlist.hold-seconds:120}") long holdSeconds,
                           @Lazy WaitlistPromoter self) {
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.bookingRepository = bookingRepository;
        this.resourceRepository = resourceRepository;
        this.userRepository = userRepository;
        this.availabilityCache = availabilityCache;
        this.availabilityEventPublisher = availabilityEventPublisher;
        this.waitlistEventPublisher = waitlistEventPublisher;
        this.holdSeconds = holdSeconds;
        this.self = self;
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

    @Override
    @Transactional
    public void promoteForReleasedSlot(SlotChange releasedSlot) {
        Resource resource = resourceRepository.findByIdForUpdate(releasedSlot.resourceId())
                .orElse(null);
        if (resource == null) {
            return;
        }
        List<WaitlistEntry> waiting = waitlistEntryRepository
                .findByResourceIdAndStatusOrderByCreatedAtAsc(resource.getId(), WaitlistStatus.WAITING);
        for (WaitlistEntry entry : waiting) {
            OffsetDateTime start = TimeRangeUtil.lower(entry.getTimeRange());
            OffsetDateTime end = TimeRangeUtil.upper(entry.getTimeRange());
            if (!bookingRepository.findOverlapping(resource.getId(), start, end).isEmpty()) {
                continue;
            }
            if (offerTo(entry, resource)) {
                return;
            }
        }
        // No eligible waiter: the slot is genuinely free.
        LocalDate date = releasedSlot.startTime().toLocalDate();
        publishAfterCommit(() -> {
            availabilityCache.evict(releasedSlot.spaceId(), date);
            availabilityEventPublisher.publishAvailable(releasedSlot);
        });
    }

    /** Creates a HELD booking for the entry and notifies the member. Returns false if the insert raced and lost. */
    private boolean offerTo(WaitlistEntry entry, Resource resource) {
        Booking hold = new Booking(resource, entry.getUser(), entry.getSpace(),
                entry.getTimeRange(), BookingStatus.HELD);
        hold.setHoldExpiresAt(Instant.now().plusSeconds(holdSeconds));
        Booking saved;
        try {
            saved = bookingRepository.saveAndFlush(hold);
        } catch (DataIntegrityViolationException ex) {
            return false;
        }
        entry.setStatus(WaitlistStatus.OFFERED);
        entry.setHeldBooking(saved);

        UUID userId = entry.getUser().getId();
        WaitlistMessage message = messageFor(entry, saved.getHoldExpiresAt(), WaitlistState.OFFERED);
        LocalDate date = TimeRangeUtil.lower(entry.getTimeRange()).toLocalDate();
        publishAfterCommit(() -> {
            availabilityCache.evict(entry.getSpace().getId(), date);
            waitlistEventPublisher.publishOffer(userId, message);
        });
        return true;
    }

    /** Claims a live offer, turning the hold into a confirmed booking. */
    @Transactional
    public BookingResponse claim(UUID userId, UUID entryId) {
        WaitlistEntry entry = waitlistEntryRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Waitlist entry not found"));
        if (!entry.getUser().getId().equals(userId)) {
            throw new ForbiddenException("You can only claim your own offer");
        }
        if (entry.getStatus() != WaitlistStatus.OFFERED || entry.getHeldBooking() == null) {
            throw new OfferExpiredException("No live offer to claim");
        }
        Booking hold = bookingRepository.findByIdForUpdate(entry.getHeldBooking().getId())
                .orElseThrow(() -> new OfferExpiredException("The offer has expired"));
        if (hold.getStatus() != BookingStatus.HELD
                || hold.getHoldExpiresAt() == null
                || hold.getHoldExpiresAt().isBefore(Instant.now())) {
            throw new OfferExpiredException("The offer has expired");
        }
        hold.setStatus(BookingStatus.CONFIRMED);
        hold.setHoldExpiresAt(null);
        entry.setStatus(WaitlistStatus.FULFILLED);

        BookingResponse response = BookingResponse.from(hold);
        WaitlistMessage message = messageFor(entry, null, WaitlistState.GRANTED);
        LocalDate date = response.startTime().toLocalDate();
        publishAfterCommit(() -> {
            availabilityCache.evict(response.spaceId(), date);
            availabilityEventPublisher.publishBooked(new SlotChange(
                    response.spaceId(), response.resourceId(), response.resourceName(),
                    response.startTime(), response.endTime()));
            waitlistEventPublisher.publishGranted(userId, message);
        });
        return response;
    }

    /** Expires one hold (called by the scheduler) and cascades to the next waiter. */
    @Transactional
    public void expireHold(UUID bookingId) {
        Booking hold = bookingRepository.findByIdForUpdate(bookingId).orElse(null);
        if (hold == null || hold.getStatus() != BookingStatus.HELD) {
            return;
        }
        if (hold.getHoldExpiresAt() == null || hold.getHoldExpiresAt().isAfter(Instant.now())) {
            return;
        }
        hold.setStatus(BookingStatus.EXPIRED);
        waitlistEntryRepository.findByHeldBooking_Id(bookingId)
                .ifPresent(entry -> entry.setStatus(WaitlistStatus.EXPIRED));
        SlotChange slot = new SlotChange(
                hold.getSpace().getId(),
                hold.getResource().getId(),
                hold.getResource().getName(),
                TimeRangeUtil.lower(hold.getTimeRange()),
                TimeRangeUtil.upper(hold.getTimeRange()));
        publishAfterCommit(() -> self.promoteForReleasedSlot(slot));
    }

    private void declineOffer(WaitlistEntry entry) {
        entry.setStatus(WaitlistStatus.CANCELLED);
        if (entry.getHeldBooking() == null) {
            return;
        }
        Booking hold = bookingRepository.findByIdForUpdate(entry.getHeldBooking().getId()).orElse(null);
        if (hold == null || hold.getStatus() != BookingStatus.HELD) {
            return;
        }
        hold.setStatus(BookingStatus.EXPIRED);
        SlotChange slot = new SlotChange(
                hold.getSpace().getId(),
                hold.getResource().getId(),
                hold.getResource().getName(),
                TimeRangeUtil.lower(hold.getTimeRange()),
                TimeRangeUtil.upper(hold.getTimeRange()));
        publishAfterCommit(() -> self.promoteForReleasedSlot(slot));
    }

    private WaitlistMessage messageFor(WaitlistEntry entry, Instant expiresAt, WaitlistState state) {
        return new WaitlistMessage(
                entry.getId(),
                entry.getResource().getId(),
                entry.getResource().getName(),
                entry.getSpace().getId(),
                TimeRangeUtil.lower(entry.getTimeRange()),
                TimeRangeUtil.upper(entry.getTimeRange()),
                expiresAt,
                state);
    }

    private void publishAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
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
