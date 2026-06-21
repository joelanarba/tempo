package com.tempoapi.exception;

import com.tempoapi.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class WaitlistExceptionHandlingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void expiredOfferMapsTo410() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleOfferExpired(new OfferExpiredException("gone"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody().isSuccess()).isFalse();
    }

    @Test
    void alreadyWaitlistedMapsTo409() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAlreadyWaitlisted(new AlreadyWaitlistedException("dup"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void slotAvailableMapsTo400() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleSlotAvailable(new SlotAvailableException("book it"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
