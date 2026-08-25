package com.seatflow.event.repository;

import com.seatflow.event.model.entity.OutboxEvent;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class OutboxEventRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_event_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void shouldStorePayloadAsJsonObjectNotString() {
        Map<String, Object> payload = Map.of("eventId", "abc-123", "type", "EVENT_PUBLISHED");
        OutboxEvent event = OutboxEvent.builder()
                .aggregateId(UUID.randomUUID())
                .eventType("EVENT_PUBLISHED")
                .payload(payload)
                .build();

        OutboxEvent saved = outboxRepository.saveAndFlush(event);

        String storedType = (String) entityManager.createNativeQuery(
                        "SELECT jsonb_typeof(payload) FROM outbox_events WHERE id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();
        assertThat(storedType).isEqualTo("object");

        OutboxEvent reloaded = outboxRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getPayload())
                .containsEntry("eventId", "abc-123")
                .containsEntry("type", "EVENT_PUBLISHED");
    }
}
