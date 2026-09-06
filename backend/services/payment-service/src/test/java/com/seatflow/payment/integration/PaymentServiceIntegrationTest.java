package com.seatflow.payment.integration;

import com.seatflow.common.events.EventTopics;
import com.seatflow.payment.PaymentServiceApplication;
import com.seatflow.payment.client.ReservationServiceClient;
import com.seatflow.payment.client.dto.ReservationClientResponse;
import com.seatflow.payment.gateway.StripePaymentGateway;
import com.seatflow.payment.gateway.dto.StripeIntentResult;
import com.seatflow.payment.model.entity.OutboxEvent;
import com.seatflow.payment.model.entity.Payment;
import com.seatflow.payment.model.enums.PaymentStatus;
import com.seatflow.payment.messaging.producer.OutboxEventPublisher;
import com.seatflow.payment.repository.OutboxEventRepository;
import com.seatflow.payment.repository.PaymentRepository;
import com.seatflow.payment.service.PaymentService;
import com.seatflow.payment.service.StripeWebhookService;
import com.seatflow.payment.web.dto.request.CreatePaymentIntentRequest;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = PaymentServiceApplication.class)
@ActiveProfiles("test")
@Testcontainers
class PaymentServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private StripeWebhookService stripeWebhookService;

    @Autowired
    private OutboxEventPublisher outboxEventPublisher;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockitoBean
    private ReservationServiceClient reservationServiceClient;

    @MockitoBean
    private StripePaymentGateway stripePaymentGateway;

    @MockitoBean
    private org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate;

    private Event mockSucceededWebhookEvent(String paymentIntentId) {
        Event event = mock(Event.class);
        when(event.getType()).thenReturn("payment_intent.succeeded");
        when(event.getId()).thenReturn("evt_webhook_1");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        PaymentIntent paymentIntent = mock(PaymentIntent.class);
        when(paymentIntent.getId()).thenReturn(paymentIntentId);
        when(deserializer.getObject()).thenReturn(Optional.of(paymentIntent));
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        return event;
    }

    @Test
    void fullPaymentLifecyclePublishesOutboxToKafka() {
        UUID reservationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        // P12-008 scenario F/A: one correlated session-A identity flows through
        // payment; session B exists only as a leakage oracle.
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        Instant startsA = Instant.parse("2026-10-05T19:00:00Z");
        Instant endsA = Instant.parse("2026-10-05T21:00:00Z");
        String paymentIntentId = "pi_integration_123";
        String idempotencyKey = "idem-integration-1";

        when(reservationServiceClient.getReservation(reservationId)).thenReturn(new ReservationClientResponse(
                reservationId,
                sessionA,
                eventId,
                null,
                "guest@example.com",
                "PENDING",
                Instant.now().plus(java.time.Duration.ofMinutes(15)),
                new BigDecimal("50.00"),
                2,
                startsA,
                endsA,
                null,
                List.of(),
                Instant.now()
        ));
        when(stripePaymentGateway.createPaymentIntent(any(), any(), any(), any(), any()))
                .thenReturn(new StripeIntentResult(paymentIntentId, "secret_integration", "requires_payment_method"));

        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // 1. Create PaymentIntent
        CreatePaymentIntentRequest request = new CreatePaymentIntentRequest(reservationId, idempotencyKey);
        paymentService.createPaymentIntent(request, null);

        // 2. Payment persisted as INITIATED
        Payment payment = paymentRepository.findByReservationId(reservationId).orElseThrow();
        UUID paymentId = payment.getId();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(payment.getStripePaymentIntentId()).isEqualTo(paymentIntentId);

        // 3. Simulate Stripe webhook success
        Event webhookEvent = mockSucceededWebhookEvent(paymentIntentId);
        try (var webhook = mockStatic(Webhook.class)) {
            webhook.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(webhookEvent);
            stripeWebhookService.handleWebhookEvent("{}", "sig");
        }

        // 4. Payment status updated to SUCCESS with the exact session-A
        // snapshot carried from the trusted reservation state.
        Payment updated = paymentRepository.findByReservationId(reservationId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(updated.getEventSessionId()).isEqualTo(sessionA);
        assertThat(updated.getSessionStartsAt()).isEqualTo(startsA);
        assertThat(updated.getSessionEndsAt()).isEqualTo(endsA);

        // 5. Unpublished PaymentCompleted outbox record exists
        assertThat(outboxEventRepository.countByAggregateIdAndEventType(paymentId, "PaymentCompleted")).isEqualTo(1);
        OutboxEvent pending = outboxEventRepository.findAll().stream()
                .filter(o -> o.getAggregateId().equals(paymentId) && "PaymentCompleted".equals(o.getEventType()))
                .findFirst()
                .orElseThrow();
        assertThat(pending.getPublishedAt()).isNull();
        String pendingPayload = pending.getPayload().toString();
        assertThat(pendingPayload).contains(sessionA.toString());
        assertThat(pendingPayload).contains(startsA.toString());
        assertThat(pendingPayload).contains(endsA.toString());
        assertThat(pendingPayload).doesNotContain(sessionB.toString());

        // 6. Trigger publisher
        outboxEventPublisher.publishPendingEvents();

        // 7. KafkaTemplate received message on the payment topic keyed by paymentId
        verify(kafkaTemplate).send(
                org.mockito.ArgumentMatchers.eq(EventTopics.PAYMENT_EVENTS),
                org.mockito.ArgumentMatchers.eq(paymentId.toString()),
                anyString()
        );

        // 8. Outbox record now has published_at set
        OutboxEvent published = outboxEventRepository.findAll().stream()
                .filter(o -> o.getAggregateId().equals(paymentId) && "PaymentCompleted".equals(o.getEventType()))
                .findFirst()
                .orElseThrow();
        assertThat(published.getPublishedAt()).isNotNull();

        // 9. Duplicate webhook delivery stays idempotent: replaying the same
        // Stripe success produces no second payment row and no second
        // PaymentCompleted outbox event (one logical payment set).
        Event duplicateWebhookEvent = mockSucceededWebhookEvent(paymentIntentId);
        try (var webhook = mockStatic(Webhook.class)) {
            webhook.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(duplicateWebhookEvent);
            stripeWebhookService.handleWebhookEvent("{}", "sig");
        }

        assertThat(paymentRepository.findByReservationId(reservationId)).isPresent();
        assertThat(outboxEventRepository.countByAggregateIdAndEventType(paymentId, "PaymentCompleted")).isEqualTo(1);
        Payment afterReplay = paymentRepository.findByReservationId(reservationId).orElseThrow();
        assertThat(afterReplay.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(afterReplay.getEventSessionId()).isEqualTo(sessionA);
    }

    @Test
    void brokerPublishFailureLeavesCommittedOutboxRetryableAndPaymentIntact() {
        // P12-008 outbox failure path (publisher leg, REV-005): the payment +
        // PaymentCompleted outbox commit together in the webhook transaction,
        // and a broker failure must leave the committed outbox row
        // unpublished/retryable without touching the authoritative payment.
        // (The aggregate leg — forced outbox-write failure rolls back the
        // whole webhook transaction — lives in
        // PaymentOutboxWriteFailureIntegrationTest.)
        UUID reservationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        Instant startsA = Instant.parse("2026-10-05T19:00:00Z");
        Instant endsA = Instant.parse("2026-10-05T21:00:00Z");
        String paymentIntentId = "pi_broker_fail_123";
        String idempotencyKey = "idem-broker-fail-1";

        when(reservationServiceClient.getReservation(reservationId)).thenReturn(new ReservationClientResponse(
                reservationId,
                sessionA,
                eventId,
                null,
                "guest@example.com",
                "PENDING",
                Instant.now().plus(java.time.Duration.ofMinutes(15)),
                new BigDecimal("50.00"),
                2,
                startsA,
                endsA,
                null,
                List.of(),
                Instant.now()
        ));
        when(stripePaymentGateway.createPaymentIntent(any(), any(), any(), any(), any()))
                .thenReturn(new StripeIntentResult(paymentIntentId, "secret_broker_fail", "requires_payment_method"));

        paymentService.createPaymentIntent(new CreatePaymentIntentRequest(reservationId, idempotencyKey), null);

        Payment payment = paymentRepository.findByReservationId(reservationId).orElseThrow();
        UUID paymentId = payment.getId();

        Event webhookEvent = mockSucceededWebhookEvent(paymentIntentId);
        try (var webhook = mockStatic(Webhook.class)) {
            webhook.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(webhookEvent);
            stripeWebhookService.handleWebhookEvent("{}", "sig");
        }

        OutboxEvent pending = outboxEventRepository.findAll().stream()
                .filter(o -> o.getAggregateId().equals(paymentId) && "PaymentCompleted".equals(o.getEventType()))
                .findFirst()
                .orElseThrow();
        assertThat(pending.getPublishedAt()).isNull();
        int retryBefore = pending.getRetryCount();

        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("forced broker failure")));

        outboxEventPublisher.publishPendingEvents();

        OutboxEvent after = outboxEventRepository.findAll().stream()
                .filter(o -> o.getAggregateId().equals(paymentId) && "PaymentCompleted".equals(o.getEventType()))
                .findFirst()
                .orElseThrow();
        assertThat(after.getPublishedAt()).isNull();
        assertThat(after.getRetryCount()).isGreaterThan(retryBefore);

        Payment stored = paymentRepository.findByReservationId(reservationId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(stored.getEventSessionId()).isEqualTo(sessionA);
        assertThat(stored.getSessionStartsAt()).isEqualTo(startsA);
        assertThat(stored.getSessionEndsAt()).isEqualTo(endsA);
    }
}
