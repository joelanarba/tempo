package com.tempoapi.modules.waitlist;

import com.tempoapi.common.PageResponse;
import com.tempoapi.entity.Booking;
import com.tempoapi.entity.BookingStatus;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.User;
import com.tempoapi.entity.WaitlistEntry;
import com.tempoapi.entity.WaitlistStatus;
import com.tempoapi.exception.AlreadyWaitlistedException;
import com.tempoapi.exception.ForbiddenException;
import com.tempoapi.exception.OfferExpiredException;
import com.tempoapi.exception.ResourceNotFoundException;
import com.tempoapi.exception.SlotAvailableException;
import com.tempoapi.exception.ValidationException;
import com.tempoapi.modules.availability.AvailabilityChanges;
import com.tempoapi.modules.bookings.dto.BookingResponse;
import com.tempoapi.modules.waitlist.dto.JoinWaitlistRequest;
import com.tempoapi.modules.waitlist.dto.WaitlistEntryResponse;
import com.tempoapi.repository.BookingRepository;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.UserRepository;
import com.tempoapi.repository.WaitlistEntryRepository;
import com.tempoapi.util.AfterCommit;
import com.tempoapi.util.BookingTimes;
import com.tempoapi.util.TimeRangeUtil;
import com.tempoapi.websocket.SlotChange;
import com.tempoapi.websocket.WaitlistEventPublisher;
import com.tempoapi.websocket.WaitlistMessage;
import com.tempoapi.websocket.WaitlistState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class WaitlistService implements WaitlistPromoter {
    private final WaitlistEntryRepository waitlistEntryRepository;
    private final BookingRepository bookingRepository;
    private final ResourceRepository resourceRepository;
    private final UserRepository userRepository;
    private final AvailabilityChanges availabilityChanges;
    private final WaitlistEventPublisher waitlistEventPublisher;
    private final long holdSeconds;
    private final Clock clock;

    public WaitlistService(WaitlistEntryRepository waitlistEntryRepository,
                           BookingRepository bookingRepository,
                           ResourceRepository resourceRepository,
                           UserRepository userRepository,
                           AvailabilityChanges availabilityChanges,
                           WaitlistEventPublisher waitlistEventPublisher,
                           @Value("${app.waitlist.hold-seconds:120}") long holdSeconds,
                           Clock clock) {
        if (holdSeconds < 1) {
            throw new IllegalArgumentException("Waitlist hold duration must be positive");
        }
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.bookingRepository = bookingRepository;
        this.resourceRepository = resourceRepository;
        this.userRepository = userRepository;
        this.availabilityChanges = availabilityChanges;
        this.waitlistEventPublisher = waitlistEventPublisher;
        this.holdSeconds = holdSeconds;
        this.clock = clock;
    }

    @Transactional
    public WaitlistEntryResponse join(UUID userId, JoinWaitlistRequest request) {
        BookingTimes.validateFuture(request.startTime(), request.endTime(), clock.instant());
        Resource resource = lockResource(request.resourceId());
        if (!resource.isActive() || !resource.getSpace().isActive()) {
            throw new ResourceNotFoundException("Resource is not available");
        }
        BookingTimes.validateFuture(request.startTime(), request.endTime(), clock.instant());
        BookingTimes.validateHours(resource.getSpace(), request.startTime(), request.endTime());
        OffsetDateTime start = TimeRangeUtil.toUtc(request.startTime());
        OffsetDateTime end = TimeRangeUtil.toUtc(request.endTime());
        List<Booking> occupied = bookingRepository.findOverlapping(resource.getId(), start, end);
        if (occupied.isEmpty()) {
            throw new SlotAvailableException("The slot is available; book it directly instead of waitlisting");
        }
        if (occupied.stream().noneMatch(booking -> sameSlot(booking, start, end))) {
            throw new ValidationException("Waitlist times must match an existing booking or hold");
        }
        if (waitlistEntryRepository.existsActiveEntry(resource.getId(), userId, start, end)) {
            throw new AlreadyWaitlistedException("Already on the waitlist for this slot");
        }
        User user = userRepository.getReferenceById(userId);
        if (!user.isActive()) {
            throw new ForbiddenException("Account is disabled");
        }
        WaitlistEntry entry = new WaitlistEntry(resource, resource.getSpace(), user,
                TimeRangeUtil.toRange(start, end), WaitlistStatus.WAITING);
        entry = waitlistEntryRepository.saveAndFlush(entry);
        return WaitlistEntryResponse.from(entry, positionOf(entry));
    }

    @Transactional(readOnly = true)
    public PageResponse<WaitlistEntryResponse> listMine(UUID userId, Pageable pageable) {
        return PageResponse.from(waitlistEntryRepository.findByUserId(userId, pageable)
                .map(entry -> WaitlistEntryResponse.from(entry, null)));
    }

    @Transactional
    public void leaveOrDecline(UUID userId, UUID entryId) {
        Resource resource = lockEntryResource(entryId);
        WaitlistEntry entry = ownedEntry(userId, entryId);
        if (entry.getStatus() == WaitlistStatus.WAITING) {
            entry.setStatus(WaitlistStatus.CANCELLED);
            return;
        }
        if (entry.getStatus() != WaitlistStatus.OFFERED || entry.getHeldBooking() == null) {
            throw new ValidationException("Waitlist entry is not active");
        }
        Booking hold = bookingRepository.findByIdForUpdate(entry.getHeldBooking().getId())
                .orElseThrow(() -> new OfferExpiredException("The offer has expired"));
        if (hold.getStatus() != BookingStatus.HELD) {
            throw new OfferExpiredException("The offer has expired");
        }
        entry.setStatus(WaitlistStatus.CANCELLED);
        hold.setStatus(BookingStatus.EXPIRED);
        bookingRepository.flush();
        promoteLocked(resource, slotOf(hold));
    }

    /** Release and promotion share the caller's transaction and resource lock. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void promoteForReleasedSlot(SlotChange releasedSlot) {
        promoteLocked(lockResource(releasedSlot.resourceId()), releasedSlot);
    }

    private void promoteLocked(Resource resource, SlotChange releasedSlot) {
        List<WaitlistEntry> waiting = waitlistEntryRepository.findWaitingForSlot(resource.getId(),
                TimeRangeUtil.toUtc(releasedSlot.startTime()), TimeRangeUtil.toUtc(releasedSlot.endTime()));
        for (WaitlistEntry entry : waiting) {
            if (!eligible(entry, resource)) {
                entry.setStatus(WaitlistStatus.EXPIRED);
                continue;
            }
            OffsetDateTime start = TimeRangeUtil.lower(entry.getTimeRange());
            OffsetDateTime end = TimeRangeUtil.upper(entry.getTimeRange());
            if (!bookingRepository.findOverlapping(resource.getId(), start, end).isEmpty()) {
                availabilityChanges.changed(releasedSlot.spaceId());
                return;
            }
            offerTo(entry, resource);
            return;
        }
        // A direct database writer can bypass the resource lock, so recheck occupancy.
        if (bookingRepository.findOverlapping(resource.getId(), releasedSlot.startTime(), releasedSlot.endTime()).isEmpty()) {
            availabilityChanges.available(releasedSlot);
        } else {
            availabilityChanges.changed(releasedSlot.spaceId());
        }
    }

    private void offerTo(WaitlistEntry entry, Resource resource) {
        Booking hold = new Booking(resource, entry.getUser(), resource.getSpace(), entry.getTimeRange(), BookingStatus.HELD);
        Instant expiresAt = clock.instant().plusSeconds(holdSeconds);
        Instant startsAt = TimeRangeUtil.lower(entry.getTimeRange()).toInstant();
        hold.setHoldExpiresAt(expiresAt.isBefore(startsAt) ? expiresAt : startsAt);
        // A failed flush aborts the entire release transaction; never continue in an aborted transaction.
        hold = bookingRepository.saveAndFlush(hold);
        entry.setStatus(WaitlistStatus.OFFERED);
        entry.setHeldBooking(hold);
        availabilityChanges.booked(slotOf(hold));
        UUID userId = entry.getUser().getId();
        WaitlistMessage message = messageFor(entry, hold.getHoldExpiresAt(), WaitlistState.OFFERED);
        AfterCommit.run(() -> waitlistEventPublisher.publishOffer(userId, message));
    }

    @Transactional
    public BookingResponse claim(UUID userId, UUID entryId) {
        Resource resource = lockEntryResource(entryId);
        WaitlistEntry entry = ownedEntry(userId, entryId);
        if (entry.getStatus() != WaitlistStatus.OFFERED || entry.getHeldBooking() == null) {
            throw new OfferExpiredException("No live offer to claim");
        }
        Booking hold = bookingRepository.findByIdForUpdate(entry.getHeldBooking().getId())
                .orElseThrow(() -> new OfferExpiredException("The offer has expired"));
        if (hold.getStatus() != BookingStatus.HELD || hold.getHoldExpiresAt() == null
                || !clock.instant().isBefore(hold.getHoldExpiresAt()) || !eligible(entry, resource)) {
            throw new OfferExpiredException("The offer has expired or is no longer available");
        }
        hold.setStatus(BookingStatus.CONFIRMED);
        hold.setHoldExpiresAt(null);
        entry.setStatus(WaitlistStatus.FULFILLED);
        availabilityChanges.booked(slotOf(hold));
        WaitlistMessage message = messageFor(entry, null, WaitlistState.GRANTED);
        AfterCommit.run(() -> waitlistEventPublisher.publishGranted(userId, message));
        return BookingResponse.from(hold);
    }

    @Transactional
    public void expireHold(UUID bookingId) {
        UUID resourceId = bookingRepository.findResourceIdById(bookingId).orElse(null);
        if (resourceId == null) {
            return;
        }
        Resource resource = lockResource(resourceId);
        Booking hold = bookingRepository.findByIdForUpdate(bookingId).orElse(null);
        if (hold == null || hold.getStatus() != BookingStatus.HELD || hold.getHoldExpiresAt() == null
                || clock.instant().isBefore(hold.getHoldExpiresAt())) {
            return;
        }
        hold.setStatus(BookingStatus.EXPIRED);
        waitlistEntryRepository.findByHeldBooking_Id(bookingId)
                .ifPresent(entry -> entry.setStatus(WaitlistStatus.EXPIRED));
        bookingRepository.flush();
        promoteLocked(resource, slotOf(hold));
    }

    private Resource lockEntryResource(UUID entryId) {
        UUID resourceId = waitlistEntryRepository.findResourceIdById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Waitlist entry not found"));
        return lockResource(resourceId);
    }

    private Resource lockResource(UUID resourceId) {
        // All booking and queue mutations take this lock before entry or booking locks.
        return resourceRepository.findByIdForUpdate(resourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Resource not found"));
    }

    private WaitlistEntry ownedEntry(UUID userId, UUID entryId) {
        WaitlistEntry entry = waitlistEntryRepository.findByIdForUpdate(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Waitlist entry not found"));
        if (!entry.getUser().getId().equals(userId)) {
            throw new ForbiddenException("You can only manage your own waitlist entry");
        }
        return entry;
    }

    private boolean eligible(WaitlistEntry entry, Resource resource) {
        if (!resource.isActive() || !resource.getSpace().isActive() || !entry.getUser().isActive()) {
            return false;
        }
        try {
            BookingTimes.validateFuture(TimeRangeUtil.lower(entry.getTimeRange()),
                    TimeRangeUtil.upper(entry.getTimeRange()), clock.instant());
            BookingTimes.validateHours(resource.getSpace(), TimeRangeUtil.lower(entry.getTimeRange()),
                    TimeRangeUtil.upper(entry.getTimeRange()));
            return true;
        } catch (ValidationException ex) {
            return false;
        }
    }

    private boolean sameSlot(Booking booking, OffsetDateTime start, OffsetDateTime end) {
        return booking.getTimeRange() != null
                && start.isEqual(TimeRangeUtil.lower(booking.getTimeRange()))
                && end.isEqual(TimeRangeUtil.upper(booking.getTimeRange()));
    }

    private int positionOf(WaitlistEntry entry) {
        List<WaitlistEntry> waiting = waitlistEntryRepository.findWaitingForSlot(entry.getResource().getId(),
                TimeRangeUtil.lower(entry.getTimeRange()), TimeRangeUtil.upper(entry.getTimeRange()));
        for (int i = 0; i < waiting.size(); i++) {
            if (entry.getId() != null && entry.getId().equals(waiting.get(i).getId())) {
                return i + 1;
            }
        }
        return waiting.size() + 1;
    }

    private SlotChange slotOf(Booking booking) {
        return new SlotChange(booking.getSpace().getId(), booking.getResource().getId(), booking.getResource().getName(),
                TimeRangeUtil.lower(booking.getTimeRange()), TimeRangeUtil.upper(booking.getTimeRange()));
    }

    private WaitlistMessage messageFor(WaitlistEntry entry, Instant expiresAt, WaitlistState state) {
        return new WaitlistMessage(entry.getId(), entry.getResource().getId(), entry.getResource().getName(),
                entry.getSpace().getId(), TimeRangeUtil.lower(entry.getTimeRange()),
                TimeRangeUtil.upper(entry.getTimeRange()), expiresAt, state);
    }
}
