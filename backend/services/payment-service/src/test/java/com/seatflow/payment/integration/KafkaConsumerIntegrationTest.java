package com.seatflow.payment.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.payment.client.ReservationServiceClient;
import com.seatflow.payment.gateway.StripePaymentGateway;
import com.seatflow.payment.messaging.event.UserRegisteredEvent;
import com.seatflow.payment.model.entity.Payment;
import com.seatflow.payment.model.enums.PaymentStatus;
import com.seatflow.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(topics = {EventTopics.USER_EVENTS})
class KafkaConsumerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_payment_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("outbox.publisher.fixed-delay-ms", () -> "60000");
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private ReservationServiceClient reservationServiceClient;

    @MockitoBean
    private StripePaymentGateway stripePaymentGateway;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void userRegisteredLinksGuestPaymentsToNewAccount() throws Exception {
        String guestEmail = "guest-link-" + UUID.randomUUID() + "@seatflow.com";
        UUID reservationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        // 1. Create a guest payment record with userId = null
        Payment guestPayment = Payment.builder()
                .reservationId(reservationId)
                .userId(null)
                .customerEmail(guestEmail)
                .eventId(eventId)
                .stripePaymentIntentId("pi_guest_" + UUID.randomUUID())
                .idempotencyKey("idem-guest-" + UUID.randomUUID())
                .amount(new BigDecimal("99.99"))
                .currency("USD")
                .status(PaymentStatus.SUCCESS)
                .build();

        Payment savedPayment = paymentRepository.saveAndFlush(guestPayment);
        UUID paymentId = savedPayment.getId();
        assertThat(savedPayment.getUserId()).isNull();

        // 2. Publish UserRegisteredEvent to seatflow.user.events
        UUID newUserId = UUID.randomUUID();
        UserRegisteredEvent evt = new UserRegisteredEvent(newUserId, guestEmail, Instant.now());
        String json = objectMapper.writeValueAsString(
                EventEnvelope.of("UserRegistered", newUserId.toString(), UUID.randomUUID().toString(), evt));

        kafkaTemplate.send(EventTopics.USER_EVENTS, newUserId.toString(), json).get();

        // 3. Await consumer execution and verify userId is linked
        Payment linkedPayment = awaitUserId(paymentId, newUserId, Duration.ofSeconds(15));
        assertThat(linkedPayment.getUserId()).isEqualTo(newUserId);
    }

    @Test
    void duplicateUserRegisteredEventIsHandledIdempotently() throws Exception {
        String guestEmail = "guest-idem-" + UUID.randomUUID() + "@seatflow.com";
        UUID reservationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        Payment guestPayment = Payment.builder()
                .reservationId(reservationId)
                .userId(null)
                .customerEmail(guestEmail)
                .eventId(eventId)
                .stripePaymentIntentId("pi_idem_" + UUID.randomUUID())
                .idempotencyKey("idem-dup-" + UUID.randomUUID())
                .amount(new BigDecimal("49.99"))
                .currency("USD")
                .status(PaymentStatus.SUCCESS)
                .build();

        Payment savedPayment = paymentRepository.saveAndFlush(guestPayment);
        UUID paymentId = savedPayment.getId();

        UUID newUserId = UUID.randomUUID();
        UserRegisteredEvent evt = new UserRegisteredEvent(newUserId, guestEmail, Instant.now());
        String json = objectMapper.writeValueAsString(
                EventEnvelope.of("UserRegistered", newUserId.toString(), UUID.randomUUID().toString(), evt));

        // First delivery
        kafkaTemplate.send(EventTopics.USER_EVENTS, newUserId.toString(), json).get();
        awaitUserId(paymentId, newUserId, Duration.ofSeconds(15));

        // Second delivery (duplicate replay)
        kafkaTemplate.send(EventTopics.USER_EVENTS, newUserId.toString(), json).get();

        // Give a short delay to allow re-processing and verify no corruption or failure occurs
        Thread.sleep(1000);
        Payment paymentAfterReplay = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(paymentAfterReplay.getUserId()).isEqualTo(newUserId);
    }

    @Test
    void nonMatchingEventTypeIsIgnoredSafely() throws Exception {
        String guestEmail = "guest-unrelated-" + UUID.randomUUID() + "@seatflow.com";
        UUID reservationId = UUID.randomUUID();

        Payment guestPayment = Payment.builder()
                .reservationId(reservationId)
                .userId(null)
                .customerEmail(guestEmail)
                .eventId(UUID.randomUUID())
                .stripePaymentIntentId("pi_unrelated_" + UUID.randomUUID())
                .idempotencyKey("idem-unrel-" + UUID.randomUUID())
                .amount(new BigDecimal("25.00"))
                .currency("USD")
                .status(PaymentStatus.SUCCESS)
                .build();

        Payment savedPayment = paymentRepository.saveAndFlush(guestPayment);
        UUID paymentId = savedPayment.getId();

        String nonMatchingJson = """
                {
                    "eventId": "%s",
                    "eventType": "UserProfileUpdated",
                    "aggregateId": "%s",
                    "correlationId": "%s",
                    "version": 1,
                    "payload": {
                        "email": "%s"
                    }
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), guestEmail);

        kafkaTemplate.send(EventTopics.USER_EVENTS, UUID.randomUUID().toString(), nonMatchingJson).get();

        Thread.sleep(1000);
        Payment unlinked = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(unlinked.getUserId()).isNull();
    }

    private Payment awaitUserId(UUID paymentId, UUID expectedUserId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Payment p = paymentRepository.findById(paymentId).orElse(null);
            if (p != null && expectedUserId.equals(p.getUserId())) {
                return p;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Payment " + paymentId + " was not linked to user " + expectedUserId);
    }
}
