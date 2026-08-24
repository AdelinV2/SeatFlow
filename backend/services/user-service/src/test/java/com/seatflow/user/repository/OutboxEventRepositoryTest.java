package com.seatflow.user.repository;

import com.seatflow.user.model.entity.OutboxEvent;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class OutboxEventRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_user_test")
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
    private OutboxEventRepository outboxEventRepository;

    @Test
    void shouldFindUnpublishedEventsOrderedByCreatedAt() {
        OutboxEvent event1 = OutboxEvent.builder()
                .aggregateId(UUID.randomUUID())
                .eventType("UserRegistered")
                .payload("{\"userId\":\"test\"}")
                .build();
        OutboxEvent event2 = OutboxEvent.builder()
                .aggregateId(UUID.randomUUID())
                .eventType("UserRegistered")
                .payload("{\"userId\":\"test2\"}")
                .build();
        OutboxEvent publishedEvent = OutboxEvent.builder()
                .aggregateId(UUID.randomUUID())
                .eventType("UserRegistered")
                .payload("{\"userId\":\"published\"}")
                .publishedAt(Instant.now())
                .build();

        outboxEventRepository.saveAll(List.of(event1, event2, publishedEvent));

        List<OutboxEvent> unpublished = outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        assertThat(unpublished).hasSize(2);
        assertThat(unpublished).noneMatch(e -> e.getPublishedAt() != null);
    }

    @Test
    void shouldReturnEmptyWhenAllEventsPublished() {
        OutboxEvent event = OutboxEvent.builder()
                .aggregateId(UUID.randomUUID())
                .eventType("UserRegistered")
                .payload("{\"userId\":\"all-published\"}")
                .publishedAt(Instant.now())
                .build();
        outboxEventRepository.save(event);

        List<OutboxEvent> unpublished = outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        assertThat(unpublished).isEmpty();
    }
}
