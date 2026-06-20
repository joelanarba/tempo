package com.tempoapi.util;

import com.tempoapi.config.JwtProperties;
import com.tempoapi.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Generates and validates the access and refresh JWTs.
 *
 * <p>Access tokens carry the user id (subject), role and a {@code type=access} claim;
 * refresh tokens carry only the subject and {@code type=refresh}. Every token has a
 * unique {@code jti} so individual access tokens can be blocklisted on logout.
 */
@Component
public class JwtUtil {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final long accessExpiryMs;
    private final long refreshExpiryMs;

    public JwtUtil(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.accessExpiryMs = properties.getAccessExpiryMs();
        this.refreshExpiryMs = properties.getRefreshExpiryMs();
    }

    /** Builds a signed access token for the given user. */
    public String generateAccessToken(UUID userId, Role role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim(CLAIM_ROLE, role.name())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessExpiryMs)))
                .signWith(key)
                .compact();
    }

    /** Builds a signed refresh token for the given user. */
    public String generateRefreshToken(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(refreshExpiryMs)))
                .signWith(key)
                .compact();
    }

    /** Verifies the signature and expiry and returns the claims, or throws {@link io.jsonwebtoken.JwtException}. */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID extractUserId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    public String extractRole(Claims claims) {
        return claims.get(CLAIM_ROLE, String.class);
    }

    public String extractJti(Claims claims) {
        return claims.getId();
    }

    public Instant extractExpiration(Claims claims) {
        return claims.getExpiration().toInstant();
    }

    public boolean isAccessToken(Claims claims) {
        return TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public boolean isRefreshToken(Claims claims) {
        return TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public Instant refreshTokenExpiry() {
        return Instant.now().plusMillis(refreshExpiryMs);
    }
}
