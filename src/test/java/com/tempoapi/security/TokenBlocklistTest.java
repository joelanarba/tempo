package com.tempoapi.security;

import com.tempoapi.exception.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TokenBlocklistTest {
    private final Clock clock = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void lookupFailureDeniesAuthentication() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> new TokenBlocklist(redis, clock).isBlocklisted("token-id"))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedWriteDoesNotReportSuccessfulRevocation() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doThrow(new IllegalStateException("offline")).when(values).set(anyString(), anyString(), any(Duration.class));
        assertThatThrownBy(() -> new TokenBlocklist(redis, clock).blocklist("token-id", clock.instant().plusSeconds(60)))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void missingTokenIdIsRejected() {
        assertThat(new TokenBlocklist(mock(StringRedisTemplate.class), clock).isBlocklisted(null)).isTrue();
    }
}
