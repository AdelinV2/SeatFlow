package com.seatflow.event.messaging.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.event.model.entity.OutboxEvent;
import com.seatflow.event.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OutboxEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxEventPublisher(outboxEventRepository, kafkaTemplate, objectMapper);
    }

    private OutboxEvent sampleEvent() {
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(UUID.randomUUID())
                .eventType("EVENT_CREATED")
                .payload(Map.of("eventId", "abc", "eventType", "EVENT_CREATED"))
                .build();
    }

    @Test
    void noRows_noKafkaInteraction() {
        when(outboxEventRepository.findUnpublishedForUpdate(anyInt(), anyInt())).thenReturn(List.of());

        publisher.publishPendingEvents();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(outboxEventRepository, never()).markPublished(any(), any());
        verify(outboxEventRepository, never()).incrementRetryCount(any(), anyInt());
    }

    @Test
    void success_oneRow_sendsAndMarksPublished() {
        OutboxEvent event = sampleEvent();
        when(outboxEventRepository.findUnpublishedForUpdate(anyInt(), anyInt())).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        when(outboxEventRepository.markPublished(eq(event.getId()), any(Instant.class))).thenReturn(1);

        publisher.publishPendingEvents();

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), anyString());
        assertThat(topicCaptor.getValue()).isEqualTo("seatflow.event.events");
        assertThat(keyCaptor.getValue()).isEqualTo(event.getAggregateId().toString());
        verify(outboxEventRepository).markPublished(eq(event.getId()), any(Instant.class));
        verify(outboxEventRepository, never()).incrementRetryCount(any(), anyInt());
    }

    @Test
    void kafkaFailure_incrementsRetry_doesNotMarkPublished() {
        OutboxEvent event = sampleEvent();
        when(outboxEventRepository.findUnpublishedForUpdate(anyInt(), anyInt())).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        when(outboxEventRepository.incrementRetryCount(eq(event.getId()), anyInt())).thenReturn(1);

        publisher.publishPendingEvents();

        verify(kafkaTemplate).send(anyString(), anyString(), anyString());
        verify(outboxEventRepository, never()).markPublished(any(), any());
        verify(outboxEventRepository).incrementRetryCount(eq(event.getId()), anyInt());
    }

    @Test
    void maxRetryRow_neverClaimed() {
        when(outboxEventRepository.findUnpublishedForUpdate(anyInt(), anyInt())).thenReturn(List.of());

        publisher.publishPendingEvents();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        ArgumentCaptor<Integer> maxRetryCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(outboxEventRepository).findUnpublishedForUpdate(maxRetryCaptor.capture(), anyInt());
        assertThat(maxRetryCaptor.getValue()).isEqualTo(5);
    }

    @Test
    void zeroMarkPublishedResult_tolerated() {
        OutboxEvent event = sampleEvent();
        when(outboxEventRepository.findUnpublishedForUpdate(anyInt(), anyInt())).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        when(outboxEventRepository.markPublished(eq(event.getId()), any(Instant.class))).thenReturn(0);

        publisher.publishPendingEvents();

        verify(kafkaTemplate).send(anyString(), anyString(), anyString());
        verify(outboxEventRepository).markPublished(eq(event.getId()), any(Instant.class));
        verify(outboxEventRepository, never()).incrementRetryCount(any(), anyInt());
    }
}
