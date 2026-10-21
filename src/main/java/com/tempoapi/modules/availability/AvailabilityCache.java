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
 * {@code availability:{spaceId}:{date}:{revision}}. The database owns revisions,
 * so an older reader cannot repopulate the key used after a committed mutation.
 */
@Component
public class AvailabilityCache {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityCache.class);
    private static final String KEY_FORMAT = "availability:%s:%s:%d";

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
    public Optional<AvailabilitySnapshotResponse> get(UUID spaceId, LocalDate date, long revision) {
        try {
            String json = redisTemplate.opsForValue().get(key(spaceId, date, revision));
            if (json == null) {
                return Optional.empty();
            }
            AvailabilitySnapshotResponse snapshot = objectMapper.readValue(json, AvailabilitySnapshotResponse.class);
            return snapshot.revision() == revision && snapshot.spaceId().equals(spaceId) && snapshot.date().equals(date)
                    ? Optional.of(snapshot) : Optional.empty();
        } catch (Exception ex) {
            log.warn("Availability cache read failed ({})", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Stores the snapshot under its key with the configured TTL. */
    public void put(UUID spaceId, LocalDate date, AvailabilitySnapshotResponse snapshot) {
        try {
            redisTemplate.opsForValue().set(key(spaceId, date, snapshot.revision()), objectMapper.writeValueAsString(snapshot), ttl);
        } catch (Exception ex) {
            log.warn("Availability cache write failed ({})", ex.getClass().getSimpleName());
        }
    }

    private String key(UUID spaceId, LocalDate date, long revision) {
        return String.format(KEY_FORMAT, spaceId, date, revision);
    }
}
