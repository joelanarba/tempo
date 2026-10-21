package com.tempoapi;

import com.tempoapi.util.TokenHash;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.jdbc.ContainerDatabaseDriver;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigrationUpgradeTest extends AbstractIntegrationTest {
    @Test
    void existingRefreshCredentialIsHashedWithoutRevokingIt() {
        var source = new SimpleDriverDataSource(new ContainerDatabaseDriver(),
                "jdbc:tc:postgresql:16-alpine:///tempo_upgrade?TC_DAEMON=true", "test", "test");
        Flyway.configure().dataSource(source).target("9").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        UUID userId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        String credential = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name) VALUES (?,?,?,?,?)",
                userId, UUID.randomUUID() + "@tempo.test", "unused-test-hash", "Test", "Member");
        jdbc.update("INSERT INTO refresh_tokens(id,user_id,token,expires_at) VALUES (?,?,?,now()+interval '1 day')",
                tokenId, userId, credential);
        jdbc.update("INSERT INTO spaces(id,name,location,opening_time,closing_time) VALUES (?, 'Hub', 'UTC', '08:00', '20:00')", spaceId);
        Flyway.configure().dataSource(source).load().migrate();
        assertThat(jdbc.queryForObject("SELECT token_hash FROM refresh_tokens WHERE id=?", String.class, tokenId))
                .isEqualTo(TokenHash.of(credential));
        assertThat(jdbc.queryForObject("SELECT revoked FROM refresh_tokens WHERE id=?", Boolean.class, tokenId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT availability_revision FROM spaces WHERE id=?", Long.class, spaceId)).isZero();
    }
}
