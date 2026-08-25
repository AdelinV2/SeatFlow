package com.seatflow.seatmap.messaging.producer;

import com.seatflow.common.events.EventTopics;
import com.seatflow.seatmap.model.entity.OutboxEvent;
import com.seatflow.seatmap.repository.OutboxEventRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTest {

    @InjectMocks
    private OutboxEventPublisher publisher;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(publisher, "batchSize", 50);
        ReflectionTestUtils.setField(publisher, "topic", EventTopics.SEATMAP_EVENTS);
    }

    @Test
    void shouldPublishPendingEventsAndMarkAsPublished() {
        // Given
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String payload = "{\"venueId\":\"" + aggregateId + "\"}";
        OutboxEvent event = OutboxEvent.builder()
                .id(eventId).aggregateId(aggregateId)
                .eventType("VenueCreated")
                .payload(payload)
                .retryCount(0)
                .build();

        when(outboxEventRepository.findUnpublishedForUpdate(5, 50))
                .thenReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> successfulFuture = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq(EventTopics.SEATMAP_EVENTS), eq(aggregateId.toString()), eq(payload)))
                .thenReturn(successfulFuture);
        when(outboxEventRepository.markPublished(eq(eventId), any(Instant.class)))
                .thenReturn(1);

        // When
        publisher.publishPendingEvents();

        // Then
        verify(kafkaTemplate).send(EventTopics.SEATMAP_EVENTS, aggregateId.toString(), payload);
        verify(outboxEventRepository).markPublished(eq(eventId), any(Instant.class));
        verify(outboxEventRepository, never()).incrementRetryCount(any(), anyInt());
    }

    @Test
    void shouldIncrementRetryCountOnKafkaFailure() {
        // Given
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String payload = "{\"venueId\":\"" + aggregateId + "\"}";
        OutboxEvent event = OutboxEvent.builder()
                .id(eventId).aggregateId(aggregateId)
                .eventType("VenueCreated").payload(payload)
                .retryCount(0).build();

        when(outboxEventRepository.findUnpublishedForUpdate(5, 50))
                .thenReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka unavailable"));
        when(kafkaTemplate.send(eq(EventTopics.SEATMAP_EVENTS), eq(aggregateId.toString()), eq(payload)))
                .thenReturn(failedFuture);
        when(outboxEventRepository.incrementRetryCount(eq(eventId), eq(5)))
                .thenReturn(1);

        // When
        publisher.publishPendingEvents();

        // Then
        verify(kafkaTemplate).send(EventTopics.SEATMAP_EVENTS, aggregateId.toString(), payload);
        verify(outboxEventRepository).incrementRetryCount(eventId, 5);
        verify(outboxEventRepository, never()).markPublished(any(), any());
    }

    @Test
    void shouldDoNothingWhenNoEventsAvailable() {
        when(outboxEventRepository.findUnpublishedForUpdate(5, 50))
                .thenReturn(Collections.emptyList());

        publisher.publishPendingEvents();

        verifyNoInteractions(kafkaTemplate);
        verify(outboxEventRepository, never()).markPublished(any(), any());
        verify(outboxEventRepository, never()).incrementRetryCount(any(), anyInt());
    }
}
