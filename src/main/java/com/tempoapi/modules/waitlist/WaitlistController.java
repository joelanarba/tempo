package com.tempoapi.modules.waitlist;

import com.tempoapi.common.ApiResponse;
import com.tempoapi.common.PageResponse;
import com.tempoapi.modules.bookings.dto.BookingResponse;
import com.tempoapi.modules.waitlist.dto.JoinWaitlistRequest;
import com.tempoapi.modules.waitlist.dto.WaitlistEntryResponse;
import com.tempoapi.security.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/waitlist")
public class WaitlistController {

    private final WaitlistService waitlistService;

    public WaitlistController(WaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<WaitlistEntryResponse>> join(@Valid @RequestBody JoinWaitlistRequest request) {
        WaitlistEntryResponse response = waitlistService.join(SecurityUtils.currentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Added to waitlist", response));
    }

    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<PageResponse<WaitlistEntryResponse>>> mine(
            @PageableDefault(size = 20) Pageable pageable) {
        PageResponse<WaitlistEntryResponse> page = waitlistService.listMine(SecurityUtils.currentUserId(), pageable);
        return ResponseEntity.ok(ApiResponse.ok(page));
    }

    @DeleteMapping("/{entryId}")
    public ResponseEntity<ApiResponse<Void>> leave(@PathVariable UUID entryId) {
        waitlistService.leaveOrDecline(SecurityUtils.currentUserId(), entryId);
        return ResponseEntity.ok(ApiResponse.ok("Waitlist entry removed", null));
    }

    @PostMapping("/offers/{entryId}/claim")
    public ResponseEntity<ApiResponse<BookingResponse>> claim(@PathVariable UUID entryId) {
        BookingResponse response = waitlistService.claim(SecurityUtils.currentUserId(), entryId);
        return ResponseEntity.ok(ApiResponse.ok("Offer claimed", response));
    }
}
