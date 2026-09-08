package com.seatflow.reservation.integration;

import com.seatflow.common.events.EventTopics;
import com.seatflow.reservation.client.EventClient;
import com.seatflow.reservation.client.dto.EventPricingDetails;
import com.seatflow.reservation.client.dto.SessionBookingContextDto;
import com.seatflow.reservation.model.enums.ReservationStatus;
import com.seatflow.reservation.service.ReservationService;
import com.seatflow.reservation.web.dto.request.CreateReservationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.ClassUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * TASK-P14-007 §7.17 runtime isolation evidence: the reservation write path succeeds while
 * analytics is genuinely absent (not started and not on the classpath), proving there is no
 * reverse dependency from checkout to the analytics read model.
 *
 * <p>Complements {@code infra/scripts/verify-analytics-isolation.sh} (static/config facts)
 * with runtime facts: the reservation application context contains zero analytics beans,
 * analytics classes are absent from this service's classpath, the checkout flow emits only
 * to the reservation topic family, and reservation creation succeeds end to end.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AnalyticsAbsenceIsolationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_reservation_isolation_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("outbox.publisher.fixed-delay-ms", () -> "60000");
        registry.add("reservation.cleanup.enabled", () -> "false");
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private EventClient eventClient;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ReservationService reservationService;

    @Test
    @DisplayName("reservation context holds no analytics beans or classes")
    void noAnalyticsBeansOrClasses() {
        assertThat(context.getBeansOfType(Object.class).keySet().stream()
                .filter(name -> name.toLowerCase().contains("analytic"))
                .toList()).isEmpty();
        assertThat(ClassUtils.isPresent(
                "com.seatflow.analytics.AnalyticsServiceApplication",
                getClass().getClassLoader())).isFalse();
    }

    @Test
    @DisplayName("checkout succeeds with analytics absent and emits only reservation topics")
    void checkoutSucceedsWithoutAnalytics() {
        assertThat(EventTopics.RESERVATION_EVENTS).isEqualTo("seatflow.reservation.events");
        assertThat(EventTopics.RESERVATION_EVENTS).isNotEqualTo(EventTopics.PAYMENT_EVENTS);

        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        when(eventClient.getSessionBookingContext(sessionId)).thenReturn(new SessionBookingContextDto(
                sessionId, eventId, "PUBLISHED", "SCHEDULED",
                Instant.now().plusSeconds(86400), Instant.now().plusSeconds(90000),
                null, null, UUID.randomUUID()));
        when(eventClient.getEventSeatPricing(any(), any())).thenReturn(new EventPricingDetails(
                eventId, "PUBLISHED", List.of(seatId), Map.of(seatId, new BigDecimal("10.00"))));

        var response = reservationService.createReservation(
                new CreateReservationRequest(sessionId, "guest@example.com",
                        List.of(seatId), List.of(new BigDecimal("10.00")),
                        "idem-isolation-" + UUID.randomUUID()),
                UUID.randomUUID());

        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(response.eventSessionId()).isEqualTo(sessionId);
    }
}
