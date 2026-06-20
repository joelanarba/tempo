package com.tempoapi.modules.auth.dto;

public record AccessTokenResponse(
        String accessToken,
        String tokenType) {
}
