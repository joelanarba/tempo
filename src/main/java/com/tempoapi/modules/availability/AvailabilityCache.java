package com.tempoapi.modules.availability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tempoapi.modules.availability.dto.AvailabilitySnapshotResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-backed read cache for availability snapshots, keyed by
 * {@code availability:{spaceId}:{date}}. Values are stored as JSON with a short TTL;
 * the cache fails open (a Redis error is treated as a miss / no-op) so availability
 * always stays correct even if Redis is unavailable.
 */
@Component
public class AvailabilityCache {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityCache.class);
    private static final String KEY_FORMAT = "availability:%s:%s";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public AvailabilityCache(StringRedisTemplate redisTemplate,
                             ObjectMapper objectMapper,
                             @Value("${app.availability.cache-ttl-seconds:30}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /** Returns the cached snapshot if present, or empty on a miss or any Redis error. */
    public Optional<AvailabilitySnapshotResponse> get(UUID spaceId, LocalDate date) {
        try {
            String json = redisTemplate.opsForValue().get(key(spaceId, date));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, AvailabilitySnapshotResponse.class));
        } catch (Exception ex) {
            log.warn("Availability cache read failed, treating as miss: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** Stores the snapshot under its key with the configured TTL. */
    public void put(UUID spaceId, LocalDate date, AvailabilitySnapshotResponse snapshot) {
        try {
            redisTemplate.opsForValue().set(key(spaceId, date), objectMapper.writeValueAsString(snapshot), ttl);
        } catch (Exception ex) {
            log.warn("Availability cache write failed, skipping: {}", ex.getMessage());
        }
    }

    /** Removes the cached snapshot for a space and date so the next read is fresh. */
    public void evict(UUID spaceId, LocalDate date) {
        try {
            redisTemplate.delete(key(spaceId, date));
        } catch (RuntimeException ex) {
            log.warn("Availability cache eviction failed: {}", ex.getMessage());
        }
    }

    private String key(UUID spaceId, LocalDate date) {
        return String.format(KEY_FORMAT, spaceId, date);
    }
}
