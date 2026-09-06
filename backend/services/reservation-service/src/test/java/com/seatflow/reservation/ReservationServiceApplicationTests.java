package com.seatflow.reservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ReservationServiceApplicationTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_reservation_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("outbox.publisher.fixed-delay-ms", () -> "60000");
        registry.add("reservation.cleanup.enabled", () -> "false");
    }

    // Prevent the OAuth2 resource server from performing a network call to the dummy issuer on startup.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
        // Context startup success is asserted implicitly by SpringBootTest.
    }

    @Test
    void flywayMigrationsAppliedSuccessfully() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank ASC", String.class);

        assertThat(versions)
                // P12-007: V8 is the fail-closed session-integrity gate.
                // P12-009: V9 promotes the gate to NOT NULL.
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");

        List<Boolean> successes = jdbcTemplate.queryForList(
                "SELECT success FROM flyway_schema_history ORDER BY installed_rank ASC", Boolean.class);

        assertThat(successes).containsExactly(true, true, true, true, true, true, true, true, true);
    }

    @Test
    void criticalConstraintsArePresent() {
        List<String> constraintNames = jdbcTemplate.queryForList(
                """
                SELECT conname
                FROM pg_constraint
                WHERE conname IN ('chk_res_seat_count', 'uq_reservations_idempotency_key')
                """, String.class);

        assertThat(constraintNames)
                .containsExactlyInAnyOrder(
                        "chk_res_seat_count",
                        "uq_reservations_idempotency_key");

        // Session-scoped partial unique index (Zero Double-Booking guarantee per session) is a plain index, not a pg_constraint row.
        List<String> indexNames = jdbcTemplate.queryForList(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE indexname IN (
                    'uq_active_seat_hold_session',
                    'idx_seat_holds_pricing_tier_id',
                    'idx_seat_holds_held_status')
                """, String.class);

        assertThat(indexNames).containsExactlyInAnyOrder(
                "uq_active_seat_hold_session",
                "idx_seat_holds_pricing_tier_id",
                "idx_seat_holds_held_status");

        // P12-004 immutable session schedule snapshot columns.
        List<String> snapshotColumns = jdbcTemplate.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_name = 'reservations'
                  AND column_name IN ('session_starts_at', 'session_ends_at', 'session_timezone')
                """, String.class);

        assertThat(snapshotColumns).containsExactlyInAnyOrder(
                "session_starts_at",
                "session_ends_at",
                "session_timezone");

        // P12-009: session inventory key is DB-enforced NOT NULL on both tables.
        List<String> nullableSessionColumns = jdbcTemplate.queryForList(
                """
                SELECT table_name
                FROM information_schema.columns
                WHERE column_name = 'event_session_id'
                  AND table_name IN ('reservations', 'seat_holds')
                  AND is_nullable = 'YES'
                """, String.class);

        assertThat(nullableSessionColumns).isEmpty();
    }
}
