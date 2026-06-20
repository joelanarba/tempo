package com.tempoapi.util;

import com.tempoapi.config.JwtProperties;
import com.tempoapi.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("round-trip-secret-round-trip-secret-round-trip");
        properties.setAccessExpiryMs(900_000);
        properties.setRefreshExpiryMs(604_800_000);
        jwtUtil = new JwtUtil(properties);
    }

    @Test
    void accessTokenRoundTripsSubjectAndRole() {
        UUID userId = UUID.randomUUID();
        String token = jwtUtil.generateAccessToken(userId, Role.ADMIN);

        Claims claims = jwtUtil.parse(token);

        assertThat(jwtUtil.extractUserId(claims)).isEqualTo(userId);
        assertThat(jwtUtil.extractRole(claims)).isEqualTo("ADMIN");
        assertThat(jwtUtil.isAccessToken(claims)).isTrue();
        assertThat(jwtUtil.isRefreshToken(claims)).isFalse();
        assertThat(jwtUtil.extractJti(claims)).isNotBlank();
    }

    @Test
    void refreshTokenCarriesSubjectButNoRole() {
        UUID userId = UUID.randomUUID();
        String token = jwtUtil.generateRefreshToken(userId);

        Claims claims = jwtUtil.parse(token);

        assertThat(jwtUtil.extractUserId(claims)).isEqualTo(userId);
        assertThat(jwtUtil.isRefreshToken(claims)).isTrue();
        assertThat(jwtUtil.isAccessToken(claims)).isFalse();
        assertThat(jwtUtil.extractRole(claims)).isNull();
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtUtil.generateAccessToken(UUID.randomUUID(), Role.MEMBER);
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThatThrownBy(() -> jwtUtil.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithDifferentSecretIsRejected() {
        String token = jwtUtil.generateAccessToken(UUID.randomUUID(), Role.MEMBER);
        JwtProperties other = new JwtProperties();
        other.setSecret("a-totally-different-secret-a-totally-different");
        other.setAccessExpiryMs(900_000);
        other.setRefreshExpiryMs(604_800_000);
        JwtUtil otherUtil = new JwtUtil(other);

        assertThatThrownBy(() -> otherUtil.parse(token)).isInstanceOf(JwtException.class);
    }
}
