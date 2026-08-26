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
        String paymentIntentId = "pi_integration_123";
        String idempotencyKey = "idem-integration-1";

        when(reservationServiceClient.getReservation(reservationId)).thenReturn(new ReservationClientResponse(
                reservationId,
                eventId,
                null,
                "guest@example.com",
                "PENDING",
                Instant.now().plus(java.time.Duration.ofMinutes(15)),
                new BigDecimal("50.00"),
                2,
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

        // 4. Payment status updated to SUCCESS
        Payment updated = paymentRepository.findByReservationId(reservationId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PaymentStatus.SUCCESS);

        // 5. Unpublished PaymentCompleted outbox record exists
        assertThat(outboxEventRepository.countByAggregateIdAndEventType(paymentId, "PaymentCompleted")).isEqualTo(1);
        OutboxEvent pending = outboxEventRepository.findAll().stream()
                .filter(o -> o.getAggregateId().equals(paymentId) && "PaymentCompleted".equals(o.getEventType()))
                .findFirst()
                .orElseThrow();
        assertThat(pending.getPublishedAt()).isNull();

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
    }
}
