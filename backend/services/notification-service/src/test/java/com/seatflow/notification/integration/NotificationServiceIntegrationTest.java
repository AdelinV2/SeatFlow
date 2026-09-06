package com.seatflow.notification.integration;

import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.notification.client.TicketServiceClient;
import com.seatflow.notification.client.resend.ResendEmailClient;
import com.seatflow.notification.client.resend.dto.ResendEmailRequest;
import com.seatflow.notification.client.resend.dto.ResendEmailResponse;
import com.seatflow.notification.messaging.event.PaymentFailedEvent;
import com.seatflow.notification.messaging.event.ReservationHeldEvent;
import com.seatflow.notification.messaging.event.TicketIssuedEvent;
import com.seatflow.notification.model.entity.NotificationLog;
import com.seatflow.notification.model.enums.NotificationStatus;
import com.seatflow.notification.model.enums.NotificationTemplateType;
import com.seatflow.notification.repository.NotificationLogRepository;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(
        partitions = 1,
        topics = {
                EventTopics.TICKET_EVENTS,
                EventTopics.PAYMENT_EVENTS,
                EventTopics.RESERVATION_EVENTS
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_notification_e2e_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    }

    @TestConfiguration
    static class KafkaTestProducerConfig {
        @Bean
        public ProducerFactory<String, Object> producerFactory(
                @org.springframework.beans.factory.annotation.Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers
        ) {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
            return new DefaultKafkaProducerFactory<>(props);
        }

        @Bean
        public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
            return new KafkaTemplate<>(producerFactory);
        }
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private ResendEmailClient resendEmailClient;

    @MockitoBean
    private TicketServiceClient ticketServiceClient;

    // Spy (not mock): the Kafka container still invokes the real listener,
    // while the spy records invocations so a replayed duplicate can be proven
    // consumed (not merely slow) before asserting single-log dedupe.
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.seatflow.notification.messaging.consumer.TicketIssuedEventListener ticketIssuedEventListener;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    @BeforeEach
    void setUp() {
        when(resendEmailClient.sendEmail(any(ResendEmailRequest.class)))
                .thenReturn(new ResendEmailResponse("email_test_" + UUID.randomUUID()));
    }

    @Test
    @DisplayName("Should consume TicketIssued event, fetch PDF, dispatch email via Resend, and persist SENT log")
    void shouldProcessTicketIssuedKafkaEvent() {
        UUID ticketId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        when(ticketServiceClient.fetchTicketPdf(ticketId)).thenReturn(new byte[]{0x25, 0x50, 0x44, 0x46});

        TicketIssuedEvent event = new TicketIssuedEvent(
                ticketId,
                reservationId,
                userId,
                "e2e-ticket@example.com",
                "Jane Doe",
                UUID.randomUUID(),
                eventId,
                Instant.parse("2026-10-05T19:00:00Z"),
                Instant.parse("2026-10-05T21:00:00Z"),
                null,
                seatId,
                new BigDecimal("150.00"),
                new BigDecimal("25.00"),
                new BigDecimal("125.00"),
                "SF-TKT-E2E-001",
                "SF:QR:E2E",
                Instant.now()
        );

        EventEnvelope<TicketIssuedEvent> envelope = new EventEnvelope<>(
                UUID.randomUUID().toString(),
                "TicketIssued",
                Instant.now(),
                "corr-e2e-ticket",
                null,
                ticketId.toString(),
                1,
                event
        );

        kafkaTemplate.send(EventTopics.TICKET_EVENTS, ticketId.toString(), envelope);

        String expectedIdempotencyKey = "ticket-issued-" + ticketId;

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Optional<NotificationLog> logOptional = notificationLogRepository.findByIdempotencyKey(expectedIdempotencyKey);
                    assertThat(logOptional).isPresent();
                    NotificationLog notificationLog = logOptional.get();
                    assertThat(notificationLog.getStatus()).isEqualTo(NotificationStatus.SENT);
                    assertThat(notificationLog.getRecipientEmail()).isEqualTo("e2e-ticket@example.com");
                    assertThat(notificationLog.getTemplateType()).isEqualTo(NotificationTemplateType.TICKET_ISSUED);
                    assertThat(notificationLog.getRenderedContent()).isNotBlank().contains("SF-TKT-E2E-001");
                    assertThat(notificationLog.getSentAt()).isNotNull();
                    assertThat(notificationLog.getErrorMessage()).isNull();
                });
    }

    @Test
    @DisplayName("Should consume PaymentFailed event, dispatch alert email, and persist SENT log")
    void shouldProcessPaymentFailedKafkaEvent() {
        UUID paymentId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        PaymentFailedEvent event = new PaymentFailedEvent(
                paymentId,
                reservationId,
                UUID.randomUUID(),
                "e2e-payment-fail@example.com",
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                null,
                new BigDecimal("80.00"),
                "USD",
                "pi_e2e_failed",
                "Card declined by processor",
                Instant.now()
        );

        EventEnvelope<PaymentFailedEvent> envelope = new EventEnvelope<>(
                UUID.randomUUID().toString(),
                "PaymentFailed",
                Instant.now(),
                "corr-e2e-pay",
                null,
                paymentId.toString(),
                1,
                event
        );

        kafkaTemplate.send(EventTopics.PAYMENT_EVENTS, paymentId.toString(), envelope);

        String expectedIdempotencyKey = "payment-failed-" + paymentId;

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Optional<NotificationLog> logOptional = notificationLogRepository.findByIdempotencyKey(expectedIdempotencyKey);
                    assertThat(logOptional).isPresent();
                    NotificationLog notificationLog = logOptional.get();
                    assertThat(notificationLog.getStatus()).isEqualTo(NotificationStatus.SENT);
                    assertThat(notificationLog.getRecipientEmail()).isEqualTo("e2e-payment-fail@example.com");
                    assertThat(notificationLog.getTemplateType()).isEqualTo(NotificationTemplateType.PAYMENT_FAILED);
                });
    }

    @Test
    @DisplayName("Should consume ReservationHeld event, dispatch countdown reminder email, and persist SENT log")
    void shouldProcessReservationHeldKafkaEvent() {
        UUID reservationId = UUID.randomUUID();

        ReservationHeldEvent event = new ReservationHeldEvent(
                reservationId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "e2e-res-held@example.com",
                List.of(UUID.randomUUID(), UUID.randomUUID()),
                Instant.now().plusSeconds(900),
                new BigDecimal("200.00"),
                Instant.now()
        );

        EventEnvelope<ReservationHeldEvent> envelope = new EventEnvelope<>(
                UUID.randomUUID().toString(),
                "ReservationHeld",
                Instant.now(),
                "corr-e2e-res",
                null,
                reservationId.toString(),
                1,
                event
        );

        kafkaTemplate.send(EventTopics.RESERVATION_EVENTS, reservationId.toString(), envelope);

        String expectedIdempotencyKey = "reservation-held-" + reservationId;

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Optional<NotificationLog> logOptional = notificationLogRepository.findByIdempotencyKey(expectedIdempotencyKey);
                    assertThat(logOptional).isPresent();
                    NotificationLog notificationLog = logOptional.get();
                    assertThat(notificationLog.getStatus()).isEqualTo(NotificationStatus.SENT);
                    assertThat(notificationLog.getRecipientEmail()).isEqualTo("e2e-res-held@example.com");
                    assertThat(notificationLog.getTemplateType()).isEqualTo(NotificationTemplateType.RESERVATION_HELD);
                });
    }

    @Test
    @DisplayName("P12-008 scenario F/A: TicketIssued renders the exact session-A showing and a replayed duplicate stays one SENT log")
    void shouldRenderExactSessionAndDedupeReplayedTicketIssued() throws Exception {
        UUID ticketId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        Instant startsA = Instant.parse("2026-10-05T19:00:00Z");
        Instant endsA = Instant.parse("2026-10-05T21:00:00Z");
        // Session B exists only as a leakage oracle: the rendered showing must
        // never reference it.
        Instant startsB = startsA.plusSeconds(86400);
        String ticketCode = "SF-TKT-P12-008-A";

        when(ticketServiceClient.fetchTicketPdf(ticketId)).thenReturn(new byte[]{0x25, 0x50, 0x44, 0x46});

        TicketIssuedEvent event = new TicketIssuedEvent(
                ticketId,
                reservationId,
                userId,
                "session-a@example.com",
                "Session A Guest",
                sessionA,
                eventId,
                startsA,
                endsA,
                null,
                seatId,
                new BigDecimal("150.00"),
                new BigDecimal("25.00"),
                new BigDecimal("125.00"),
                ticketCode,
                "SF:QR:P12-008-A",
                Instant.now()
        );

        EventEnvelope<TicketIssuedEvent> envelope = new EventEnvelope<>(
                UUID.randomUUID().toString(),
                "TicketIssued",
                Instant.now(),
                "corr-p12-008-a",
                null,
                ticketId.toString(),
                1,
                event
        );

        kafkaTemplate.send(EventTopics.TICKET_EVENTS, ticketId.toString(), envelope);

        String expectedIdempotencyKey = "ticket-issued-" + ticketId;
        String expectedShowing = com.seatflow.notification.service.impl.NotificationServiceImpl
                .formatSessionInstant(startsA, null);
        String forbiddenShowing = com.seatflow.notification.service.impl.NotificationServiceImpl
                .formatSessionInstant(startsB, null);

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Optional<NotificationLog> logOptional =
                            notificationLogRepository.findByIdempotencyKey(expectedIdempotencyKey);
                    assertThat(logOptional).isPresent();
                    NotificationLog notificationLog = logOptional.get();
                    assertThat(notificationLog.getStatus()).isEqualTo(NotificationStatus.SENT);
                    assertThat(notificationLog.getTemplateType()).isEqualTo(NotificationTemplateType.TICKET_ISSUED);
                    assertThat(notificationLog.getRenderedContent()).isNotBlank()
                            .contains(ticketCode)
                            .contains(expectedShowing)
                            .doesNotContain(forbiddenShowing);
                });

        // Replay the identical TicketIssued envelope: the duplicate must be
        // demonstrably consumed before asserting single-log dedupe. The dedupe
        // path writes no new row, so no DB predicate can prove consumption —
        // and a bare verify-timeout records listener ENTRY, before the
        // transaction necessarily completes. The post-return latch below
        // decrements only after callRealMethod returns, i.e. after the
        // listener transaction commits or the idempotent early-return
        // completes (P12-008 REV-003); row assertions after the latch
        // therefore observe post-commit state by construction.
        CountDownLatch duplicateConsumed = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            try {
                return invocation.callRealMethod();
            } finally {
                duplicateConsumed.countDown();
            }
        }).when(ticketIssuedEventListener)
                .handleTicketEvent(org.mockito.ArgumentMatchers.any());

        kafkaTemplate.send(EventTopics.TICKET_EVENTS, ticketId.toString(), envelope);

        assertThat(duplicateConsumed.await(10, TimeUnit.SECONDS))
                .as("replayed TicketIssued demonstrably consumed before final assertions")
                .isTrue();

        long rowsForKey = notificationLogRepository.findAll().stream()
                .filter(log -> expectedIdempotencyKey.equals(log.getIdempotencyKey()))
                .count();
        assertThat(rowsForKey).isEqualTo(1);
        assertThat(notificationLogRepository.findByIdempotencyKey(expectedIdempotencyKey))
                .isPresent()
                .hasValueSatisfying(log -> assertThat(log.getStatus()).isEqualTo(NotificationStatus.SENT));
    }
}
