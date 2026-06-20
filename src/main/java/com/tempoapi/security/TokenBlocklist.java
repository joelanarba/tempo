package com.tempoapi.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Tracks access-token JTIs that have been invalidated by logout. Entries expire
 * automatically when the underlying token would have expired, so the set never
 * grows beyond the live token window.
 *
 * <p>The blocklist fails open: if Redis is unreachable the request is allowed to
 * proceed rather than locking every user out. Access tokens are short lived
 * (15 minutes), which bounds the exposure of this trade-off.
 */
@Component
public class TokenBlocklist {

    private static final Logger log = LoggerFactory.getLogger(TokenBlocklist.class);
    private static final String KEY_PREFIX = "blocklist:";

    private final StringRedisTemplate redisTemplate;

    public TokenBlocklist(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Blocklists the token identified by {@code jti} until {@code expiresAt}. */
    public void blocklist(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", ttl);
        } catch (RuntimeException ex) {
            log.warn("Unable to blocklist token (Redis unavailable): {}", ex.getMessage());
        }
    }

    /** Returns true if the token identified by {@code jti} has been blocklisted. */
    public boolean isBlocklisted(String jti) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
        } catch (RuntimeException ex) {
            log.warn("Blocklist lookup failed (Redis unavailable), allowing token: {}", ex.getMessage());
            return false;
        }
    }
}
