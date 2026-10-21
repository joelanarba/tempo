package com.tempoapi.modules.health;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HealthControllerTest {
    @Test
    void reportsReadyOnlyWhenBothDependenciesRespond() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(redis.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        assertThat(new HealthController(jdbc, redis).health().getStatusCode().value()).isEqualTo(200);
        verify(connection).close();
    }

    @Test
    void dependencyFailureReturns503WithoutInternalDetails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenThrow(new IllegalStateException("internal connection details"));
        var response = new HealthController(jdbc, mock(StringRedisTemplate.class)).health();
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().getMessage()).isEqualTo("Dependencies unavailable");
        assertThat(response.getBody().getData()).containsEntry("status", "DOWN");
    }
}
