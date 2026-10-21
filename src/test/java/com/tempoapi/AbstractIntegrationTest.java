package com.tempoapi;

import com.tempoapi.entity.Role;
import com.tempoapi.entity.User;
import com.tempoapi.repository.UserRepository;
import com.tempoapi.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import org.junit.jupiter.api.Tag;

/**
 * Base class for integration tests. PostgreSQL is provided by the Testcontainers
 * JDBC URL in application-test.properties; Redis is provided here as a singleton
 * container shared across the whole test suite (started once, reused, never stopped
 * explicitly so the JVM tears it down at exit).
 *
 * <p>Subclasses annotated with {@code @SpringBootTest} also get autowired auth
 * helpers for creating users and minting tokens.
 */
@ActiveProfiles("test")
@Tag("integration")
public abstract class AbstractIntegrationTest {

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:tc:postgresql:16-alpine:///tempo?TC_DAEMON=true");
        registry.add("spring.datasource.driver-class-name", () -> "org.testcontainers.jdbc.ContainerDatabaseDriver");
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("spring.datasource.username", () -> "test");
        registry.add("spring.datasource.password", () -> "test");
    }

    @Autowired(required = false)
    protected UserRepository userRepository;
    @Autowired(required = false)
    protected PasswordEncoder passwordEncoder;
    @Autowired(required = false)
    protected JwtUtil jwtUtil;

    /** Persists a fresh user with the given role and a unique email. */
    protected User persistUser(Role role) {
        User user = new User(
                "user-" + UUID.randomUUID() + "@tempo.africa",
                passwordEncoder.encode("Pa55word!"),
                "Test",
                "User",
                role);
        return userRepository.save(user);
    }

    /** Returns a Bearer header value for a freshly persisted user of the given role. */
    protected String bearerFor(Role role) {
        return "Bearer " + jwtUtil.generateAccessToken(persistUser(role).getId(), role);
    }

    /** Returns a Bearer header value for an existing user. */
    protected String bearerFor(User user) {
        return "Bearer " + jwtUtil.generateAccessToken(user.getId(), user.getRole());
    }
}
