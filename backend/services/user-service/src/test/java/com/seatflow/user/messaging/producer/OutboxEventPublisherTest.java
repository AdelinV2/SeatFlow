package com.seatflow.user.messaging.producer;

import com.seatflow.common.events.EventTopics;
import com.seatflow.user.model.entity.OutboxEvent;
import com.seatflow.user.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTest {

    @InjectMocks
    private OutboxEventPublisher outboxEventPublisher;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(outboxEventPublisher, "batchSize", 50);
    }

    @Test
    void shouldPublishPendingEventsAndMarkAsPublished() {
        // Given
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String payload = "{\"userId\":\"" + aggregateId + "\",\"email\":\"test@example.com\"}";

        OutboxEvent event = OutboxEvent.builder()
                .id(eventId)
                .aggregateId(aggregateId)
                .eventType("UserRegistered")
                .payload(payload)
                .createdAt(Instant.now())
                .retryCount(0)
                .build();

        when(outboxEventRepository.findUnpublishedForUpdate(5, 50))
                .thenReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> successfulFuture = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq(EventTopics.USER_EVENTS), eq(aggregateId.toString()), eq(payload)))
                .thenReturn(successfulFuture);
        when(outboxEventRepository.markPublished(eq(eventId), any(Instant.class)))
                .thenReturn(1);

        // When
        outboxEventPublisher.publishPendingEvents();

        // Then
        verify(kafkaTemplate).send(EventTopics.USER_EVENTS, aggregateId.toString(), payload);
        verify(outboxEventRepository).markPublished(eq(eventId), any(Instant.class));
        verify(outboxEventRepository, never()).incrementRetryCount(any(), anyInt());
    }

    @Test
    void shouldDoNothingWhenNoUnpublishedEvents() {
        // Given
        when(outboxEventRepository.findUnpublishedForUpdate(5, 50))
                .thenReturn(Collections.emptyList());

        // When
        outboxEventPublisher.publishPendingEvents();

        // Then
        verifyNoInteractions(kafkaTemplate);
        verify(outboxEventRepository, never()).markPublished(any(), any());
        verify(outboxEventRepository, never()).incrementRetryCount(any(), anyInt());
    }

    @Test
    void shouldIncrementRetryCountOnKafkaSendFailure() {
        // Given
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String payload = "{\"userId\":\"" + aggregateId + "\"}";

        OutboxEvent event = OutboxEvent.builder()
                .id(eventId)
                .aggregateId(aggregateId)
                .eventType("UserRegistered")
                .payload(payload)
                .createdAt(Instant.now())
                .retryCount(0)
                .build();

        when(outboxEventRepository.findUnpublishedForUpdate(5, 50))
                .thenReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker unavailable"));
        when(kafkaTemplate.send(eq(EventTopics.USER_EVENTS), eq(aggregateId.toString()), eq(payload)))
                .thenReturn(failedFuture);
        when(outboxEventRepository.incrementRetryCount(eq(eventId), eq(5)))
                .thenReturn(1);

        // When
        outboxEventPublisher.publishPendingEvents();

        // Then
        verify(kafkaTemplate).send(EventTopics.USER_EVENTS, aggregateId.toString(), payload);
        verify(outboxEventRepository).incrementRetryCount(eventId, 5);
        verify(outboxEventRepository, never()).markPublished(any(), any());
    }

    @Test
    void shouldHandleMultiEventBatchWithPartialFailures() {
        // Given
        UUID event1Id = UUID.randomUUID();
        UUID aggregate1Id = UUID.randomUUID();
        OutboxEvent event1 = OutboxEvent.builder()
                .id(event1Id)
                .aggregateId(aggregate1Id)
                .eventType("UserRegistered")
                .payload("{\"id\":1}")
                .createdAt(Instant.now())
                .retryCount(0)
                .build();

        UUID event2Id = UUID.randomUUID();
        UUID aggregate2Id = UUID.randomUUID();
        OutboxEvent event2 = OutboxEvent.builder()
                .id(event2Id)
                .aggregateId(aggregate2Id)
                .eventType("UserRegistered")
                .payload("{\"id\":2}")
                .createdAt(Instant.now())
                .retryCount(1)
                .build();

        when(outboxEventRepository.findUnpublishedForUpdate(5, 50))
                .thenReturn(List.of(event1, event2));

        // Event 1 succeeds
        CompletableFuture<SendResult<String, String>> successFuture = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq(EventTopics.USER_EVENTS), eq(aggregate1Id.toString()), eq("{\"id\":1}")))
                .thenReturn(successFuture);
        when(outboxEventRepository.markPublished(eq(event1Id), any(Instant.class)))
                .thenReturn(1);

        // Event 2 fails
        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka timeout"));
        when(kafkaTemplate.send(eq(EventTopics.USER_EVENTS), eq(aggregate2Id.toString()), eq("{\"id\":2}")))
                .thenReturn(failedFuture);
        when(outboxEventRepository.incrementRetryCount(eq(event2Id), eq(5)))
                .thenReturn(1);

        // When
        outboxEventPublisher.publishPendingEvents();

        // Then
        verify(outboxEventRepository).markPublished(eq(event1Id), any(Instant.class));
        verify(outboxEventRepository).incrementRetryCount(eq(event2Id), eq(5));
    }
}
