package com.tempoapi.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import com.tempoapi.exception.ServiceUnavailableException;

import java.time.Duration;
import java.time.Instant;
import java.time.Clock;

/**
 * Tracks access-token JTIs that have been invalidated by logout. Entries expire
 * automatically when the underlying token would have expired, so the set never
 * grows beyond the live token window.
 *
 * Revocation checks fail closed when Redis is unavailable.
 */
@Component
public class TokenBlocklist {

    private static final Logger log = LoggerFactory.getLogger(TokenBlocklist.class);
    private static final String KEY_PREFIX = "blocklist:";

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    public TokenBlocklist(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    /** Blocklists the token identified by {@code jti} until {@code expiresAt}. */
    public void blocklist(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(clock.instant(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", ttl);
        } catch (RuntimeException ex) {
            log.warn("Token revocation unavailable ({})", ex.getClass().getSimpleName());
            throw new ServiceUnavailableException("Authentication service unavailable");
        }
    }

    /** Returns true if the token identified by {@code jti} has been blocklisted. */
    public boolean isBlocklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return true;
        }
        try {
            Boolean blocked = redisTemplate.hasKey(KEY_PREFIX + jti);
            if (blocked == null) {
                throw new ServiceUnavailableException("Authentication service unavailable");
            }
            return blocked;
        } catch (RuntimeException ex) {
            log.warn("Token revocation lookup unavailable ({})", ex.getClass().getSimpleName());
            throw new ServiceUnavailableException("Authentication service unavailable");
        }
    }
}
