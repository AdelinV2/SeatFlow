package com.seatflow.notification.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class FlywayMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_notification_migration_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Should verify V1 migration creates notification_logs table and schema version")
    void shouldApplyV1MigrationSuccessfully() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank ASC", String.class);

        assertThat(versions).containsExactly("1");
    }

    @Test
    @DisplayName("Should verify primary key, unique, and check constraints on notification_logs")
    void shouldVerifyTableConstraints() {
        List<String> constraintNames = jdbcTemplate.queryForList(
                """
                SELECT conname
                FROM pg_constraint
                WHERE conname IN (
                    'pk_notification_logs',
                    'uq_notifications_idempotency',
                    'chk_notif_status',
                    'chk_notif_retries',
                    'chk_notif_email'
                )
                """, String.class);

        assertThat(constraintNames)
                .containsExactlyInAnyOrder(
                        "pk_notification_logs",
                        "uq_notifications_idempotency",
                        "chk_notif_status",
                        "chk_notif_retries",
                        "chk_notif_email"
                );
    }

    @Test
    @DisplayName("Should verify indexes on notification_logs table")
    void shouldVerifyIndexes() {
        List<String> indexNames = jdbcTemplate.queryForList(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE tablename = 'notification_logs'
                  AND indexname IN ('idx_notif_recipient_created', 'idx_notif_pending_retry')
                """, String.class);

        assertThat(indexNames)
                .containsExactlyInAnyOrder("idx_notif_recipient_created", "idx_notif_pending_retry");
    }

    @Test
    @DisplayName("Should verify all columns exist in notification_logs table")
    void shouldVerifyTableColumns() {
        List<String> columnNames = jdbcTemplate.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_name = 'notification_logs'
                """, String.class);

        assertThat(columnNames)
                .contains(
                        "id",
                        "recipient_email",
                        "template_type",
                        "subject",
                        "idempotency_key",
                        "rendered_content",
                        "status",
                        "error_message",
                        "sent_at",
                        "retry_count",
                        "created_at",
                        "updated_at"
                );
    }
}
