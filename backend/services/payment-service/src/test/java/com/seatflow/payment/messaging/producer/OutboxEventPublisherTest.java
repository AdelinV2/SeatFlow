package com.seatflow.payment.messaging.producer;

import com.seatflow.common.events.EventTopics;
import com.seatflow.payment.model.entity.OutboxEvent;
import com.seatflow.payment.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private MeterRegistry meterRegistry;

    @InjectMocks
    private OutboxEventPublisher publisher;

    @BeforeEach
    void setUp() {
        lenient().when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(mock(Counter.class));
    }

    private OutboxEvent outboxEvent(UUID aggregateId, String eventType, int retryCount) {
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload("{\"paymentId\":\"" + aggregateId + "\"}")
                .retryCount(retryCount)
                .build();
    }

    @Test
    void emptyUnpublishedListTriggersNoKafkaSends() {
        when(outboxEventRepository.findUnpublishedForUpdate(5, 50)).thenReturn(List.of());

        publisher.publishPendingEvents();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(outboxEventRepository, never()).markPublished(any(), any());
        verify(outboxEventRepository, never()).incrementRetryCount(any(), anyInt());
    }

    @Test
    void successfulSendMarksEventPublished() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = outboxEvent(aggregateId, "PaymentCompleted", 0);
        when(outboxEventRepository.findUnpublishedForUpdate(5, 50)).thenReturn(List.of(event));

        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();

        verify(kafkaTemplate).send(eq(EventTopics.PAYMENT_EVENTS), eq(aggregateId.toString()), eq(event.getPayload()));
        verify(outboxEventRepository).markPublished(eq(event.getId()), any(java.time.Instant.class));
        verify(outboxEventRepository, never()).incrementRetryCount(any(), anyInt());
    }

    @Test
    void brokerFailureIncrementsRetryCount() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = outboxEvent(aggregateId, "PaymentCompleted", 0);
        when(outboxEventRepository.findUnpublishedForUpdate(5, 50)).thenReturn(List.of(event));

        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker timeout")));

        publisher.publishPendingEvents();

        verify(kafkaTemplate).send(anyString(), anyString(), anyString());
        verify(outboxEventRepository).incrementRetryCount(eq(event.getId()), eq(5));
        verify(outboxEventRepository, never()).markPublished(any(), any());
    }

    @Test
    void eventAtMaxRetryIsSkippedAndLoggedAsError() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = outboxEvent(aggregateId, "PaymentCompleted", 5);
        when(outboxEventRepository.findUnpublishedForUpdate(5, 50)).thenReturn(List.of(event));

        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker timeout")));
        // Increment returns 0 => event already at max retry / already published => ERROR branch.
        when(outboxEventRepository.incrementRetryCount(eq(event.getId()), eq(5))).thenReturn(0);

        publisher.publishPendingEvents();

        verify(kafkaTemplate).send(anyString(), anyString(), anyString());
        verify(outboxEventRepository).incrementRetryCount(eq(event.getId()), eq(5));
        verify(outboxEventRepository, never()).markPublished(any(), any());
    }
}
