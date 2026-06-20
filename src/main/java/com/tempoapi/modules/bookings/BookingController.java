package com.tempoapi.modules.bookings;

import com.tempoapi.common.ApiResponse;
import com.tempoapi.common.PageResponse;
import com.tempoapi.entity.BookingStatus;
import com.tempoapi.modules.bookings.dto.BookingResponse;
import com.tempoapi.modules.bookings.dto.CreateBookingRequest;
import com.tempoapi.security.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping("/bookings")
    public ResponseEntity<ApiResponse<BookingResponse>> create(@Valid @RequestBody CreateBookingRequest request) {
        BookingResponse response = bookingService.createBooking(SecurityUtils.currentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Booking confirmed", response));
    }

    @DeleteMapping("/bookings/{id}")
    public ResponseEntity<ApiResponse<BookingResponse>> cancel(@PathVariable UUID id) {
        BookingResponse response = bookingService.cancelBooking(SecurityUtils.currentUserId(), id);
        return ResponseEntity.ok(ApiResponse.ok("Booking cancelled", response));
    }

    @GetMapping("/bookings/me")
    public ResponseEntity<ApiResponse<PageResponse<BookingResponse>>> myBookings(
            @RequestParam(required = false) BookingStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        PageResponse<BookingResponse> page =
                bookingService.listMine(SecurityUtils.currentUserId(), status, pageable);
        return ResponseEntity.ok(ApiResponse.ok(page));
    }

    @GetMapping("/bookings/{id}")
    public ResponseEntity<ApiResponse<BookingResponse>> get(@PathVariable UUID id) {
        BookingResponse response =
                bookingService.getById(SecurityUtils.currentUserId(), SecurityUtils.isAdmin(), id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/spaces/{spaceId}/bookings")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<BookingResponse>>> spaceBookings(
            @PathVariable UUID spaceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @PageableDefault(size = 50) Pageable pageable) {
        PageResponse<BookingResponse> page = bookingService.listForSpaceOnDate(spaceId, date, pageable);
        return ResponseEntity.ok(ApiResponse.ok(page));
    }
}
