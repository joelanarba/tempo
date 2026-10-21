package com.tempoapi.modules.health;

import com.tempoapi.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class HealthController {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public HealthController(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, String>>> health() {
        try {
            if (!Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class))) {
                return down();
            }
            var factory = redis.getConnectionFactory();
            if (factory == null) {
                return down();
            }
            try (RedisConnection connection = factory.getConnection()) {
                return "PONG".equals(connection.ping())
                        ? ResponseEntity.ok(ApiResponse.ok(Map.of("status", "UP"))) : down();
            }
        } catch (RuntimeException ex) {
            return down();
        }
    }

    private ResponseEntity<ApiResponse<Map<String, String>>> down() {
        return ResponseEntity.status(503).body(new ApiResponse<>(false, "Dependencies unavailable", Map.of("status", "DOWN")));
    }
}
