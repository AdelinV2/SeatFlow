package com.seatflow.reservation.messaging.producer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.seatflow.reservation.model.entity.OutboxEvent;
import com.seatflow.reservation.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
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

    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void setUp() {
        lenient().when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(mock(Counter.class));
        Logger logger = (Logger) LoggerFactory.getLogger(OutboxEventPublisher.class);
        logger.setLevel(Level.INFO);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        Logger logger = (Logger) LoggerFactory.getLogger(OutboxEventPublisher.class);
        logger.detachAppender(listAppender);
        logger.setLevel(null);
    }

    private OutboxEvent event(UUID id, String eventType, int retryCount) {
        return OutboxEvent.builder()
                .id(id)
                .aggregateId(UUID.randomUUID())
                .eventType(eventType)
                .payload("{\"type\":\"" + eventType + "\"}")
                .retryCount(retryCount)
                .build();
    }

    @Test
    void publishPendingEvents_whenEmpty_doesNotSend() {
        when(outboxEventRepository.findUnpublishedForUpdate(anyInt(), anyInt())).thenReturn(List.of());

        publisher.publishPendingEvents();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(outboxEventRepository, never()).markPublished(any(), any());
    }

    @Test
    void publishPendingEvents_whenSuccess_marksPublishedAndLogsInfo() {
        UUID id = UUID.randomUUID();
        OutboxEvent event = event(id, "ReservationHeldEvent", 0);
        when(outboxEventRepository.findUnpublishedForUpdate(anyInt(), anyInt())).thenReturn(List.of(event));
        SendResult<String, String> sendResult = mock();
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
        when(outboxEventRepository.markPublished(eq(id), any(Instant.class))).thenReturn(1);

        publisher.publishPendingEvents();

        verify(kafkaTemplate).send(eq("seatflow.reservation.events"), eq(event.getAggregateId().toString()), anyString());
        verify(outboxEventRepository).markPublished(eq(id), any(Instant.class));
        assertThat(listAppender.list).anyMatch(e ->
                e.getLevel() == Level.INFO && e.getFormattedMessage().contains("published successfully"));
    }

    @Test
    void publishPendingEvents_whenBrokerFails_incrementsRetryAndLogsWarn() {
        UUID id = UUID.randomUUID();
        OutboxEvent event = event(id, "ReservationHeldEvent", 0);
        when(outboxEventRepository.findUnpublishedForUpdate(anyInt(), anyInt())).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(failedFuture(new RuntimeException("broker down")));
        when(outboxEventRepository.incrementRetryCount(eq(id), anyInt())).thenReturn(1);

        publisher.publishPendingEvents();

        verify(outboxEventRepository, never()).markPublished(any(), any());
        verify(outboxEventRepository).incrementRetryCount(eq(id), anyInt());
        assertThat(listAppender.list).anyMatch(e ->
                e.getLevel() == Level.WARN && e.getFormattedMessage().contains("retry incremented"));
    }

    @Test
    void publishPendingEvents_whenMaxRetryExceeded_logsErrorAndSkips() {
        UUID id = UUID.randomUUID();
        // retryCount at ceiling (== MAX_RETRY_COUNT) so the increment UPDATE matches 0 rows
        OutboxEvent event = event(id, "ReservationHeldEvent", 5);
        when(outboxEventRepository.findUnpublishedForUpdate(anyInt(), anyInt())).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(failedFuture(new RuntimeException("broker down")));
        when(outboxEventRepository.incrementRetryCount(eq(id), anyInt())).thenReturn(0);

        publisher.publishPendingEvents();

        verify(outboxEventRepository, never()).markPublished(any(), any());
        assertThat(listAppender.list).anyMatch(e ->
                e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("exceeded max retry limit"));
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable ex) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(ex);
        return future;
    }
}
