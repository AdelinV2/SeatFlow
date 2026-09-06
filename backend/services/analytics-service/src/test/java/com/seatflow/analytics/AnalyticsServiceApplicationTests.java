package com.seatflow.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AnalyticsServiceApplicationTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_analytics_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
        // Assert context loads cleanly
    }

    @Test
    @DisplayName("Analytics read-model migrations apply exactly once each")
    void flywayMigrationsAppliedSuccessfully() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank ASC", String.class);

        assertThat(versions).containsExactly("1", "2");

        List<Boolean> successes = jdbcTemplate.queryForList(
                "SELECT success FROM flyway_schema_history ORDER BY installed_rank ASC", Boolean.class);

        assertThat(successes).containsExactly(true, true);
    }

    @Test
    @DisplayName("All analytics tables exist, including V2 batch-revocation facts")
    void allReadModelTablesExist() {
        List<String> tables = jdbcTemplate.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                """, String.class);

        assertThat(tables).contains(
                "processed_events",
                "analytics_session_facts",
                "analytics_reservation_facts",
                "analytics_payment_facts",
                "analytics_ticket_facts",
                "analytics_ticket_revocation_facts",
                "daily_operational_metrics",
                "daily_revenue_metrics",
                "event_session_metrics",
                "event_session_revenue_metrics"
        );
    }

    @Test
    @DisplayName("Duplicate processed_events.event_id is rejected (idempotency boundary)")
    void duplicateProcessedEventIsRejected() {
        String eventId = "evt-" + UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO processed_events (event_id, event_type, source_topic, occurred_at) VALUES (?, ?, ?, now())",
                eventId, "ReservationCreated", "seatflow.reservation.events");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO processed_events (event_id, event_type, source_topic, occurred_at) VALUES (?, ?, ?, now())",
                eventId, "ReservationCreated", "seatflow.reservation.events"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Negative counters and money are rejected by schema constraints")
    void negativeCountersAndMoneyAreRejected() {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO daily_operational_metrics
                  (metric_date, event_id, event_session_id, reservations_created, updated_at)
                VALUES (CURRENT_DATE, ?, ?, -1, now())
                """, eventId, sessionId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO daily_revenue_metrics
                  (metric_date, event_id, event_session_id, currency, gross_revenue_minor, updated_at)
                VALUES (CURRENT_DATE, ?, ?, 'RON', -100, now())
                """, eventId, sessionId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO analytics_reservation_facts
                  (reservation_id, event_id, event_session_id, created_at, seat_count,
                   last_source_event_at, updated_at)
                VALUES (?, ?, ?, now(), 0, now(), now())
                """, UUID.randomUUID(), eventId, sessionId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("capacity_snapshot accepts NULL but rejects negative values")
    void capacitySnapshotNullableButNonNegative() {
        UUID sessionId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO analytics_session_facts
                  (event_session_id, event_id, capacity_snapshot, last_source_event_at, updated_at)
                VALUES (?, ?, NULL, now(), now())
                """, sessionId, UUID.randomUUID());

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO analytics_session_facts
                  (event_session_id, event_id, capacity_snapshot, last_source_event_at, updated_at)
                VALUES (?, ?, -5, now(), now())
                """, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Operational counts stay currency-neutral while revenue coexists per currency")
    void operationalRowIsSingleWhileRevenueIsPerCurrency() {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO daily_operational_metrics
                  (metric_date, event_id, event_session_id, reservations_created, updated_at)
                VALUES (CURRENT_DATE, ?, ?, 3, now())
                """, eventId, sessionId);

        jdbcTemplate.update(
                """
                INSERT INTO daily_revenue_metrics
                  (metric_date, event_id, event_session_id, currency,
                   payments_succeeded, gross_revenue_minor, updated_at)
                VALUES (CURRENT_DATE, ?, ?, 'RON', 2, 10000, now())
                """, eventId, sessionId);
        jdbcTemplate.update(
                """
                INSERT INTO daily_revenue_metrics
                  (metric_date, event_id, event_session_id, currency,
                   payments_succeeded, gross_revenue_minor, updated_at)
                VALUES (CURRENT_DATE, ?, ?, 'EUR', 1, 5000, now())
                """, eventId, sessionId);

        Integer opsRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM daily_operational_metrics WHERE event_session_id = ?",
                Integer.class, sessionId);
        Integer revRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM daily_revenue_metrics WHERE event_session_id = ?",
                Integer.class, sessionId);

        assertThat(opsRows).isEqualTo(1);
        assertThat(revRows).isEqualTo(2);
    }

    @Test
    @DisplayName("Provisional refund fact can exist before payment completion")
    void provisionalRefundBeforeCompletionIsAllowed() {
        UUID paymentId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO analytics_payment_facts
                  (payment_id, reservation_id, currency, refunded_amount_minor,
                   refunded_at, last_source_event_at, updated_at)
                VALUES (?, ?, 'RON', 4500, now(), now(), now())
                """, paymentId, UUID.randomUUID());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analytics_payment_facts WHERE payment_id = ?",
                Integer.class, paymentId);

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Analytics tables contain no PII columns")
    void analyticsTablesContainNoPiiColumns() {
        List<String> piiColumns = jdbcTemplate.queryForList(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name IN (
                    'analytics_session_facts', 'analytics_reservation_facts',
                    'analytics_payment_facts', 'analytics_ticket_facts',
                    'analytics_ticket_revocation_facts',
                    'daily_operational_metrics', 'daily_revenue_metrics',
                    'event_session_metrics', 'event_session_revenue_metrics',
                    'processed_events')
                  AND column_name IN (
                    'email', 'customer_email', 'customer_name', 'full_name', 'first_name',
                    'last_name', 'address', 'phone', 'card_number', 'card_token',
                    'payment_method', 'jwt', 'token', 'password_hash', 'ssn', 'cnp')
                """, String.class);

        assertThat(piiColumns).isEmpty();
    }

    @Test
    @DisplayName("Analytics tables declare no foreign keys to other service tables")
    void analyticsTablesHaveNoCrossServiceForeignKeys() {
        Integer fkCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM pg_constraint
                WHERE contype = 'f'
                  AND conrelid::regclass::text IN (
                    'processed_events', 'analytics_session_facts',
                    'analytics_reservation_facts', 'analytics_payment_facts',
                    'analytics_ticket_facts', 'analytics_ticket_revocation_facts',
                    'daily_operational_metrics', 'daily_revenue_metrics',
                    'event_session_metrics', 'event_session_revenue_metrics')
                """, Integer.class);

        assertThat(fkCount).isZero();
    }
}
