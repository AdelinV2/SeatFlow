package com.seatflow.ticket;

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
class TicketServiceApplicationTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_ticket_test")
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
                .containsExactly("1", "2", "3");

        List<Boolean> successes = jdbcTemplate.queryForList(
                "SELECT success FROM flyway_schema_history ORDER BY installed_rank ASC", Boolean.class);

        assertThat(successes).containsExactly(true, true, true);
    }

    @Test
    void criticalConstraintsArePresent() {
        List<String> constraintNames = jdbcTemplate.queryForList(
                """
                SELECT conname
                FROM pg_constraint
                WHERE conname IN (
                    'pk_tickets',
                    'uq_tickets_ticket_code',
                    'uq_tickets_reservation_seat',
                    'chk_tickets_status',
                    'chk_tickets_price',
                    'chk_tickets_tax_amount',
                    'chk_tickets_net_amount',
                    'chk_tickets_email',
                    'pk_ticket_validations',
                    'fk_validations_tickets',
                    'chk_validations_result',
                    'pk_ticket_outbox',
                    'chk_ticket_outbox_retry'
                )
                """, String.class);

        assertThat(constraintNames)
                .containsExactlyInAnyOrder(
                        "pk_tickets",
                        "uq_tickets_ticket_code",
                        "uq_tickets_reservation_seat",
                        "chk_tickets_status",
                        "chk_tickets_price",
                        "chk_tickets_tax_amount",
                        "chk_tickets_net_amount",
                        "chk_tickets_email",
                        "pk_ticket_validations",
                        "fk_validations_tickets",
                        "chk_validations_result",
                        "pk_ticket_outbox",
                        "chk_ticket_outbox_retry");

        // Partial unique index (one valid ticket per seat per event) is a plain index, not a pg_constraint row.
        List<String> indexNames = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE indexname = 'uq_tickets_event_seat_valid'", String.class);

        assertThat(indexNames).containsExactly("uq_tickets_event_seat_valid");
    }
}
