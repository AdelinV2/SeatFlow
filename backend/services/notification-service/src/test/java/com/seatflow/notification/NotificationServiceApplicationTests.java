package com.seatflow.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class NotificationServiceApplicationTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_notification_test")
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
    void flywayMigrationsAppliedSuccessfully() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank ASC", String.class);

        assertThat(versions).containsExactly("1");

        List<Boolean> successes = jdbcTemplate.queryForList(
                "SELECT success FROM flyway_schema_history ORDER BY installed_rank ASC", Boolean.class);

        assertThat(successes).containsExactly(true);
    }

    @Test
    void criticalConstraintsAndIndexesArePresent() {
        List<String> constraintNames = jdbcTemplate.queryForList(
                """
                SELECT conname FROM pg_constraint
                WHERE conrelid = 'notification_logs'::regclass
                """, String.class);

        assertThat(constraintNames).contains(
                "pk_notification_logs",
                "uq_notifications_idempotency",
                "chk_notif_status",
                "chk_notif_retries",
                "chk_notif_email"
        );

        List<String> indexNames = jdbcTemplate.queryForList(
                """
                SELECT indexname FROM pg_indexes
                WHERE tablename = 'notification_logs'
                """, String.class);

        assertThat(indexNames).contains(
                "idx_notif_recipient_created",
                "idx_notif_pending_retry"
        );
    }
}
