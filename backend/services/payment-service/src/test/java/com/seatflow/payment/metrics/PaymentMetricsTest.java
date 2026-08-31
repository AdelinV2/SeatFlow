package com.seatflow.payment.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatflow.common.observability.metrics.SeatFlowMetricNames;
import com.seatflow.common.observability.tracing.W3cTraceContextPropagator;
import com.seatflow.payment.client.ReservationServiceClient;
import com.seatflow.payment.config.StripeConfig;
import com.seatflow.payment.gateway.StripePaymentGateway;
import com.seatflow.payment.mapper.PaymentMapper;
import com.seatflow.payment.messaging.producer.OutboxEventPublisher;
import com.seatflow.payment.model.entity.OutboxEvent;
import com.seatflow.payment.model.entity.Payment;
import com.seatflow.payment.model.enums.PaymentStatus;
import com.seatflow.payment.repository.OutboxEventRepository;
import com.seatflow.payment.repository.PaymentRepository;
import com.seatflow.payment.service.impl.PaymentServiceImpl;
import com.seatflow.payment.service.impl.StripeWebhookServiceImpl;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeError;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentMetricsTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private ReservationServiceClient reservationServiceClient;
    @Mock private StripePaymentGateway stripePaymentGateway;
    @Mock private PaymentMapper paymentMapper;
    @Mock private StripeConfig stripeConfig;
    @Mock private W3cTraceContextPropagator propagator;

    private MeterRegistry registry;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private Payment stubPayment(UUID id, PaymentStatus status) {
        return Payment.builder()
                .id(id)
                .reservationId(UUID.randomUUID())
                .customerEmail("guest@example.com")
                .eventId(UUID.randomUUID())
                .stripePaymentIntentId("pi_test_123")
                .clientSecret("cs_test")
                .idempotencyKey("idem")
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .status(status)
                .build();
    }

    @Test
    void shouldIncrementProcessedCounterOnSyncedSucceededPayment() {
        PaymentServiceImpl service = new PaymentServiceImpl(paymentRepository, outboxEventRepository, objectMapper,
                reservationServiceClient, stripePaymentGateway, paymentMapper, registry, propagator);

        UUID paymentId = UUID.randomUUID();
        Payment payment = stubPayment(paymentId, PaymentStatus.INITIATED);
        PaymentIntent intent = mock(PaymentIntent.class);
        when(intent.getStatus()).thenReturn("succeeded");

        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(stripePaymentGateway.retrievePaymentIntent(payment.getStripePaymentIntentId())).thenReturn(intent);
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentMapper.toResponse(any(Payment.class))).thenReturn(mock(com.seatflow.payment.web.dto.response.PaymentResponse.class));

        service.getPaymentById(paymentId, null, true, null);

        assertThat(registry.find("seatflow.payments.processed.total").tags("status", "SUCCESS", "currency", "USD", "payment_method", "CARD").counter()).isNotNull();
        assertThat(registry.find("seatflow.payments.processed.total").tags("status", "SUCCESS", "currency", "USD", "payment_method", "CARD").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("seatflow.payments.processed.total").tags("status", "SUCCESS", "currency", "USD", "payment_method", "CARD").counter().getId().getTag("paymentId")).isNull();
        assertThat(registry.find("seatflow.payments.processed.total").tags("status", "SUCCESS", "currency", "USD", "payment_method", "CARD").counter().getId().getTag("stripePaymentIntentId")).isNull();
    }

    @Test
    void shouldIncrementProcessedCounterOnWebhookSucceeded() {
        StripeWebhookServiceImpl webhookService = new StripeWebhookServiceImpl(stripeConfig, paymentRepository, outboxEventRepository, objectMapper, registry, propagator);
        UUID paymentId = UUID.randomUUID();
        Payment payment = stubPayment(paymentId, PaymentStatus.INITIATED);
        when(paymentRepository.findByStripePaymentIntentId("pi_test_123")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_test_123");

        // invoke private method via reflection for test simplicity
        try {
            var method = StripeWebhookServiceImpl.class.getDeclaredMethod("handlePaymentIntentSucceeded", PaymentIntent.class, com.google.gson.JsonObject.class);
            method.setAccessible(true);
            method.invoke(webhookService, pi, new com.google.gson.JsonObject());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThat(registry.find("seatflow.payments.processed.total").tags("status", "SUCCESS", "currency", "USD", "payment_method", "CARD").counter()).isNotNull();
        assertThat(registry.find("seatflow.payments.processed.total").tags("status", "SUCCESS").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("seatflow.payments.completed.total").tags("status", "SUCCESS").counter()).isNotNull();
    }

    @Test
    void shouldIncrementProcessedCounterOnWebhookFailed() {
        StripeWebhookServiceImpl webhookService = new StripeWebhookServiceImpl(stripeConfig, paymentRepository, outboxEventRepository, objectMapper, registry, propagator);
        UUID paymentId = UUID.randomUUID();
        Payment payment = stubPayment(paymentId, PaymentStatus.INITIATED);
        when(paymentRepository.findByStripePaymentIntentId("pi_test_123")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_test_123");
        StripeError err = mock(StripeError.class);
        when(err.getMessage()).thenReturn("card_declined");
        when(pi.getLastPaymentError()).thenReturn(err);

        try {
            var method = StripeWebhookServiceImpl.class.getDeclaredMethod("handlePaymentIntentFailed", PaymentIntent.class);
            method.setAccessible(true);
            method.invoke(webhookService, pi);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThat(registry.find("seatflow.payments.processed.total").tags("status", "FAILED", "currency", "USD").counter()).isNotNull();
        assertThat(registry.find("seatflow.payments.processed.total").tags("status", "FAILED").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("seatflow.payments.failed.total").tags("reason", "STRIPE_FAILED").counter()).isNotNull();
    }

    @Test
    void shouldNotContainHighCardinalityTags() throws Exception {
        StripeWebhookServiceImpl webhookService = new StripeWebhookServiceImpl(stripeConfig, paymentRepository, outboxEventRepository, objectMapper, registry, propagator);
        UUID paymentId = UUID.randomUUID();
        Payment payment = stubPayment(paymentId, PaymentStatus.INITIATED);
        when(paymentRepository.findByStripePaymentIntentId("pi_test_123")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_test_123");
        var method = StripeWebhookServiceImpl.class.getDeclaredMethod("handlePaymentIntentSucceeded", PaymentIntent.class, com.google.gson.JsonObject.class);
        method.setAccessible(true);
        method.invoke(webhookService, pi, new com.google.gson.JsonObject());

        // verify no high-cardinality tags
        var counter = registry.find("seatflow.payments.processed.total").tags("status", "SUCCESS", "currency", "USD", "payment_method", "CARD").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.getId().getTag("paymentId")).isNull();
        assertThat(counter.getId().getTag("stripePaymentIntentId")).isNull();
        assertThat(counter.getId().getTag("userId")).isNull();
    }

    @Test
    void shouldRecordOutboxLatencyOnPublisherSuccess() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        var publisher = new OutboxEventPublisher(
                outboxEventRepository, kafkaTemplate, registry, objectMapper, propagator);
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(UUID.randomUUID())
                .eventType("PaymentCompleted")
                .payload(objectMapper.createObjectNode())
                .createdAt(Instant.now().minusSeconds(5))
                .build();
        when(outboxEventRepository.findUnpublishedForUpdate(5, 50)).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(String.class), any(String.class), any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(outboxEventRepository.markPublished(any(UUID.class), any(Instant.class))).thenReturn(1);

        publisher.publishPendingEvents();

        assertThat(registry.find("seatflow.outbox.publish.latency").timer()).isNotNull();
        assertThat(registry.find("seatflow.outbox.publish.latency").tags("service", "payment-service", "event_type", "PaymentCompleted", "outcome", "SUCCESS").timer()).isNotNull();
    }

    @Test
    void shouldNotRecordSuccessfulOutboxLatencyWhenPublishedStateIsNotPersisted() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        var publisher = new OutboxEventPublisher(
                outboxEventRepository, kafkaTemplate, registry, objectMapper, propagator);
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(UUID.randomUUID())
                .eventType("PaymentCompleted")
                .payload(objectMapper.createObjectNode())
                .retryCount(0)
                .createdAt(Instant.now().minusSeconds(1))
                .build();
        when(outboxEventRepository.findUnpublishedForUpdate(5, 50)).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(String.class), any(String.class), any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(outboxEventRepository.markPublished(any(UUID.class), any(Instant.class))).thenReturn(0);

        publisher.publishPendingEvents();

        assertThat(registry.find("seatflow.outbox.publish.latency")
                .tags("service", "payment-service", "event_type", "PaymentCompleted", "outcome", "SUCCESS")
                .timer()).isNull();
    }

    @Test
    void shouldNotRecordSuccessfulOutboxLatencyWhenKafkaSendFails() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        var publisher = new OutboxEventPublisher(
                outboxEventRepository, kafkaTemplate, registry, objectMapper, propagator);
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(UUID.randomUUID())
                .eventType("PaymentCompleted")
                .payload(objectMapper.createObjectNode())
                .retryCount(0)
                .createdAt(Instant.now().minusSeconds(1))
                .build();
        when(outboxEventRepository.findUnpublishedForUpdate(5, 50)).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(String.class), any(String.class), any(String.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        when(outboxEventRepository.incrementRetryCount(event.getId(), 5)).thenReturn(1);

        publisher.publishPendingEvents();

        assertThat(registry.find("seatflow.outbox.publish.latency")
                .tags("service", "payment-service", "event_type", "PaymentCompleted", "outcome", "SUCCESS")
                .timer()).isNull();
        assertThat(registry.find(SeatFlowMetricNames.OUTBOX_RETRY_COUNT)
                .tags("service", "payment-service", "event_type", "PaymentCompleted")
                .counter()).isNotNull();
    }
}
