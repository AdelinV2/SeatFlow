package com.seatflow.payment.integration;

import com.seatflow.payment.PaymentServiceApplication;
import com.seatflow.payment.client.ReservationServiceClient;
import com.seatflow.payment.client.dto.ReservationClientResponse;
import com.seatflow.payment.gateway.StripePaymentGateway;
import com.seatflow.payment.gateway.dto.StripeIntentResult;
import com.seatflow.payment.model.enums.PaymentStatus;
import com.seatflow.payment.repository.OutboxEventRepository;
import com.seatflow.payment.repository.PaymentRepository;
import com.seatflow.payment.service.PaymentService;
import com.seatflow.payment.service.StripeWebhookService;
import com.seatflow.payment.web.dto.request.CreatePaymentIntentRequest;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P12-008 outbox failure path (payment aggregate leg, REV-005): a failure at
 * the outbox-write boundary inside the Stripe webhook transaction must roll
 * back the whole aggregate change — the payment stays INITIATED with no
 * PaymentCompleted outbox row.
 *
 * <p>Uses a real PostgreSQL (Testcontainers) with only the outbox repository
 * replaced by a mock that throws on {@code save}, so the production webhook
 * transaction boundary is exercised end to end. The publisher leg (broker
 * failure leaves the committed outbox unpublished/retryable with the payment
 * untouched) lives in
 * {@code PaymentServiceIntegrationTest#brokerPublishFailureLeavesCommittedOutboxRetryableAndPaymentIntact}.
 */
@SpringBootTest(classes = PaymentServiceApplication.class)
@ActiveProfiles("test")
@Testcontainers
class PaymentOutboxWriteFailureIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("outbox.publisher.fixed-delay-ms", () -> "60000");
    }

    @MockitoBean
    private ReservationServiceClient reservationServiceClient;

    @MockitoBean
    private StripePaymentGateway stripePaymentGateway;

    @MockitoBean
    private org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private StripeWebhookService stripeWebhookService;

    @Autowired
    private PaymentRepository paymentRepository;

    @BeforeEach
    void stubPublisherPoll() {
        // Keep the background outbox publisher idle: it only polls.
        lenient().when(outboxEventRepository.findUnpublishedForUpdate(anyInt(), anyInt()))
                .thenReturn(List.of());
    }

    private void stubPaymentPipeline(UUID reservationId, UUID sessionA, UUID eventId,
                                     Instant startsA, Instant endsA,
                                     String paymentIntentId, String idempotencyKey) {
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
                .thenReturn(new StripeIntentResult(paymentIntentId, "secret_outbox_fail", "requires_payment_method"));

        paymentService.createPaymentIntent(new CreatePaymentIntentRequest(reservationId, idempotencyKey), null);
    }

    private Event mockSucceededWebhookEvent(String paymentIntentId) {
        Event event = mock(Event.class);
        when(event.getType()).thenReturn("payment_intent.succeeded");
        when(event.getId()).thenReturn("evt_webhook_outbox_fail");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        PaymentIntent paymentIntent = mock(PaymentIntent.class);
        when(paymentIntent.getId()).thenReturn(paymentIntentId);
        when(deserializer.getObject()).thenReturn(Optional.of(paymentIntent));
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        return event;
    }

    @Test
    void webhookSuccessWithOutboxWriteFailureRollsBackPaymentStatus() {
        UUID reservationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        Instant startsA = Instant.parse("2026-10-05T19:00:00Z");
        Instant endsA = Instant.parse("2026-10-05T21:00:00Z");
        String paymentIntentId = "pi_outbox_fail_123";
        stubPaymentPipeline(reservationId, sessionA, eventId, startsA, endsA,
                paymentIntentId, "idem-outbox-fail-" + UUID.randomUUID());

        // Force the failure exactly at the outbox-write boundary: the payment
        // status update succeeds first, then the outbox save throws inside the
        // same webhook transaction.
        when(outboxEventRepository.save(any()))
                .thenThrow(new RuntimeException("forced outbox-write failure"));

        Event webhookEvent = mockSucceededWebhookEvent(paymentIntentId);
        try (var webhook = org.mockito.Mockito.mockStatic(Webhook.class)) {
            webhook.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(webhookEvent);
            // Production wraps the boundary failure (StripeWebhookServiceImpl
            // throws "Failed to persist payment outbox event" with the forced
            // failure as cause); the unchecked wrapper still rolls back the
            // whole webhook transaction.
            assertThatThrownBy(() -> stripeWebhookService.handleWebhookEvent("{}", "sig"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to persist payment outbox event")
                    .hasStackTraceContaining("forced outbox-write failure");
        }

        verify(outboxEventRepository).save(any());

        // Complete rollback: the payment is still INITIATED (never SUCCESS),
        // with column defaults untouched (tax/net 0.00 per V3 DDL, never the
        // webhook enrichment) and no failure marking.
        var stored = paymentRepository.findByReservationId(reservationId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(stored.getTaxAmount()).isEqualByComparingTo("0.00");
        assertThat(stored.getNetAmount()).isEqualByComparingTo("0.00");
        assertThat(stored.getFailureReason()).isNull();
        assertThat(stored.getEventSessionId()).isEqualTo(sessionA);
    }

    @Test
    void successfulWebhookStillWritesOutboxAfterFailure() {
        // Sanity: with a working outbox boundary the same flow commits both
        // the SUCCESS status and the PaymentCompleted outbox write.
        UUID reservationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        Instant startsA = Instant.parse("2026-10-05T19:00:00Z");
        Instant endsA = Instant.parse("2026-10-05T21:00:00Z");
        String paymentIntentId = "pi_outbox_ok_123";
        stubPaymentPipeline(reservationId, sessionA, eventId, startsA, endsA,
                paymentIntentId, "idem-outbox-ok-" + UUID.randomUUID());

        Event webhookEvent = mockSucceededWebhookEvent(paymentIntentId);
        try (var webhook = org.mockito.Mockito.mockStatic(Webhook.class)) {
            webhook.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(webhookEvent);
            stripeWebhookService.handleWebhookEvent("{}", "sig");
        }

        var stored = paymentRepository.findByReservationId(reservationId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verify(outboxEventRepository).save(any());
    }
}
