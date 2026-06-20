package com.tempoapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the Flyway migrations apply on a fresh Postgres, including the
 * btree_gist extension and the GiST exclusion constraint that the whole system
 * depends on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void btreeGistExtensionIsEnabled() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'btree_gist'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void bookingsExclusionConstraintExists() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'bookings_no_overlap' AND contype = 'x'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void allCoreTablesExist() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' "
                        + "AND table_name IN ('users','refresh_tokens','spaces','resources','bookings')",
                Integer.class);
        assertThat(count).isEqualTo(5);
    }
}
