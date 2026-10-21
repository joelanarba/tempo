package com.tempoapi.modules.bookings;

import com.tempoapi.common.PageResponse;
import com.tempoapi.entity.Booking;
import com.tempoapi.entity.BookingStatus;
import com.tempoapi.entity.Resource;
import com.tempoapi.entity.Space;
import com.tempoapi.entity.User;
import com.tempoapi.exception.ForbiddenException;
import com.tempoapi.exception.ResourceNotFoundException;
import com.tempoapi.exception.SlotUnavailableException;
import com.tempoapi.exception.ValidationException;
import com.tempoapi.modules.availability.AvailabilityChanges;
import com.tempoapi.modules.bookings.dto.BookingResponse;
import com.tempoapi.modules.bookings.dto.CreateBookingRequest;
import com.tempoapi.modules.waitlist.WaitlistPromoter;
import com.tempoapi.repository.BookingRepository;
import com.tempoapi.repository.ResourceRepository;
import com.tempoapi.repository.SpaceRepository;
import com.tempoapi.repository.UserRepository;
import com.tempoapi.util.TimeRangeUtil;
import com.tempoapi.util.BookingTimes;
import com.tempoapi.websocket.SlotChange;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final ResourceRepository resourceRepository;
    private final SpaceRepository spaceRepository;
    private final UserRepository userRepository;
    private final AvailabilityChanges availabilityChanges;
    private final WaitlistPromoter waitlistPromoter;
    private final Clock clock;

    public BookingService(BookingRepository bookingRepository,
                          ResourceRepository resourceRepository,
                          SpaceRepository spaceRepository,
                          UserRepository userRepository,
                          AvailabilityChanges availabilityChanges,
                          WaitlistPromoter waitlistPromoter,
                          Clock clock) {
        this.bookingRepository = bookingRepository;
        this.resourceRepository = resourceRepository;
        this.spaceRepository = spaceRepository;
        this.userRepository = userRepository;
        this.availabilityChanges = availabilityChanges;
        this.waitlistPromoter = waitlistPromoter;
        this.clock = clock;
    }

    /**
     * Creates a CONFIRMED booking under pessimistic locking. The resource row is locked
     * for the duration of the transaction (SELECT ... FOR UPDATE), so concurrent attempts
     * on the same resource are serialized and the overlap check reads a consistent state.
     * The database exclusion constraint is the backstop: a violation is translated to a
     * clean {@link SlotUnavailableException}.
     */
    @Transactional
    public BookingResponse createBooking(UUID userId, CreateBookingRequest request) {
        BookingTimes.validateFuture(request.startTime(), request.endTime(), clock.instant());

        // Acquire the row lock first; everything below runs inside the locked transaction.
        Resource resource = resourceRepository.findByIdForUpdate(request.resourceId())
                .orElseThrow(() -> new ResourceNotFoundException("Resource not found"));
        if (!resource.isActive()) {
            throw new ResourceNotFoundException("Resource is not available");
        }
        Space space = resource.getSpace();
        if (!space.isActive()) {
            throw new ResourceNotFoundException("Resource is not available");
        }
        BookingTimes.validateHours(space, request.startTime(), request.endTime());
        BookingTimes.validateFuture(request.startTime(), request.endTime(), clock.instant());

        OffsetDateTime start = TimeRangeUtil.toUtc(request.startTime());
        OffsetDateTime end = TimeRangeUtil.toUtc(request.endTime());

        if (!bookingRepository.findOverlapping(resource.getId(), start, end).isEmpty()) {
            throw new SlotUnavailableException("The requested slot is no longer available");
        }

        User user = userRepository.getReferenceById(userId);
        if (!user.isActive()) {
            throw new ForbiddenException("Account is disabled");
        }
        Booking booking = new Booking(resource, user, space,
                TimeRangeUtil.toRange(request.startTime(), request.endTime()), BookingStatus.CONFIRMED);

        Booking saved;
        try {
            // Flush now so a constraint violation surfaces here, not at an opaque commit.
            saved = bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException ex) {
            if (!isOverlapViolation(ex)) {
                throw ex;
            }
            throw new SlotUnavailableException("The requested slot is no longer available");
        }

        BookingResponse response = BookingResponse.from(saved);
        SlotChange slot = toSlot(response);
        availabilityChanges.booked(slot);
        return response;
    }

    /** Cancels the caller's own CONFIRMED booking and frees the slot. */
    @Transactional
    public BookingResponse cancelBooking(UUID userId, UUID bookingId) {
        UUID resourceId = bookingRepository.findResourceIdById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        resourceRepository.findByIdForUpdate(resourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Resource not found"));
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (!booking.getUser().getId().equals(userId)) {
            throw new ForbiddenException("You can only cancel your own booking");
        }
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new ValidationException("Booking is not active");
        }
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(clock.instant());
        bookingRepository.flush();

        BookingResponse response = BookingResponse.from(booking);
        SlotChange slot = toSlot(response);
        waitlistPromoter.promoteForReleasedSlot(slot);
        return response;
    }

    /** Returns one booking; visible to its owner or to any ADMIN. */
    @Transactional(readOnly = true)
    public BookingResponse getById(UUID requesterId, boolean admin, UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (!admin && !booking.getUser().getId().equals(requesterId)) {
            throw new ForbiddenException("You cannot view this booking");
        }
        return BookingResponse.from(booking);
    }

    /** Lists the caller's bookings, optionally filtered by status. */
    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> listMine(UUID userId, BookingStatus status, Pageable pageable) {
        var page = (status == null)
                ? bookingRepository.findByUserId(userId, pageable)
                : bookingRepository.findByUserIdAndStatus(userId, status, pageable);
        return PageResponse.from(page.map(BookingResponse::from));
    }

    /** Lists every booking in a space that touches the given UTC calendar day (ADMIN). */
    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> listForSpaceOnDate(UUID spaceId, LocalDate date, Pageable pageable) {
        if (!spaceRepository.existsById(spaceId)) {
            throw new ResourceNotFoundException("Space not found");
        }
        OffsetDateTime windowStart = TimeRangeUtil.startOfDayUtc(date);
        OffsetDateTime windowEnd = TimeRangeUtil.startOfNextDayUtc(date);
        var page = bookingRepository.findInSpaceForWindow(spaceId, windowStart, windowEnd, pageable);
        return PageResponse.from(page.map(BookingResponse::from));
    }

    private boolean isOverlapViolation(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && "23P01".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private SlotChange toSlot(BookingResponse response) {
        return new SlotChange(
                response.spaceId(),
                response.resourceId(),
                response.resourceName(),
                response.startTime(),
                response.endTime());
    }

}
