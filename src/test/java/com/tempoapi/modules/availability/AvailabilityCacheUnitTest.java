package com.tempoapi.modules.availability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tempoapi.modules.availability.dto.AvailabilitySnapshotResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AvailabilityCacheUnitTest {
    @Test
    @SuppressWarnings("unchecked")
    void oldReaderCannotReplaceNewerRevision() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        Map<String, String> stored = new HashMap<>();
        doAnswer(call -> { stored.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.get(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
        AvailabilityCache cache = new AvailabilityCache(redis, new ObjectMapper().registerModule(new JavaTimeModule()), 30);
        UUID spaceId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2027, 1, 1);
        var old = new AvailabilitySnapshotResponse(spaceId, date, 1, List.of());
        var current = new AvailabilitySnapshotResponse(spaceId, date, 2, List.of());
        cache.put(spaceId, date, current);
        cache.put(spaceId, date, old);
        assertThat(cache.get(spaceId, date, 2)).contains(current);
        assertThat(stored).hasSize(2);
    }

    @Test
    void redisFailureIsACacheMiss() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("offline"));
        AvailabilityCache cache = new AvailabilityCache(redis, new ObjectMapper(), 30);
        assertThat(cache.get(UUID.randomUUID(), LocalDate.now(), 0)).isEmpty();
        assertThatCode(() -> cache.put(UUID.randomUUID(), LocalDate.now(),
                new AvailabilitySnapshotResponse(UUID.randomUUID(), LocalDate.now(), 0, List.of())))
                .doesNotThrowAnyException();
    }
}
