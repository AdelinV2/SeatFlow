package com.seatflow.common.observability.metrics;

import io.micrometer.core.instrument.Tags;
import org.junit.jupiter.api.Test;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricTagPolicyTest {

    @Test
    void shouldCreateBoundedReservationCreatedTags() {
        Tags tags = MetricTagPolicy.reservationCreated("SUCCESS");
        assertThat(tags.stream().anyMatch(t -> t.getKey().equals("status") && t.getValue().equals("SUCCESS"))).isTrue();
    }

    @Test
    void shouldBoundUnknownStatusToSuccessDefault() {
        Tags tags = MetricTagPolicy.reservationCreated("weird");
        // weird status maps to SUCCESS default for created
        assertThat(tags.stream().anyMatch(t -> t.getKey().equals("status") && t.getValue().equals("SUCCESS"))).isTrue();
    }

    @Test
    void shouldCreateBoundedConflictTags() {
        Tags tags = MetricTagPolicy.reservationConflict("ALREADY_HELD");
        assertThat(tags.stream().anyMatch(t -> t.getKey().equals("reason") && t.getValue().equals("ALREADY_HELD"))).isTrue();
    }

    @Test
    void shouldBoundUnknownReasonToUnknown() {
        Tags tags = MetricTagPolicy.reservationConflict("RANDOM_FOO");
        assertThat(tags.stream().anyMatch(t -> t.getKey().equals("reason") && t.getValue().equals("UNKNOWN"))).isTrue();
    }

    @Test
    void shouldRejectForbiddenTagKeys() {
        assertThatThrownBy(() -> MetricTagPolicy.validateTagKey("userId"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forbidden");
        assertThatThrownBy(() -> MetricTagPolicy.validateTagKey("reservationId"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetricTagPolicy.validateTagKey("paymentId"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetricTagPolicy.validateTagKey("ticketId"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetricTagPolicy.validateTagKey("seatId"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetricTagPolicy.validateTagKey("eventId"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetricTagPolicy.validateTagKey("stripePaymentIntentId"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetricTagPolicy.validateTagKey("traceId"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetricTagPolicy.validateTagKey("url"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectHighCardinalityTagValues() {
        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        assertThatThrownBy(() -> MetricTagPolicy.validateTagValue(uuid))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetricTagPolicy.validateTagValue("pi_1234567890abcdef"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetricTagPolicy.validateTagValue("https://example.com/foo"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldValidateBoundedPaymentTags() {
        Tags tags = MetricTagPolicy.paymentProcessed("SUCCESS", "USD", "CARD");
        assertThat(tags.stream().anyMatch(t -> t.getKey().equals("status"))).isTrue();
        assertThat(tags.stream().anyMatch(t -> t.getKey().equals("currency") && t.getValue().equals("USD"))).isTrue();
        assertThat(tags.stream().anyMatch(t -> t.getKey().equals("payment_method") && t.getValue().equals("CARD"))).isTrue();
    }

    @Test
    void shouldHandleCurrencyObject() {
        Tags tags = MetricTagPolicy.paymentProcessed("SUCCESS", Currency.getInstance("USD"), "CARD");
        assertThat(tags.stream().anyMatch(t -> t.getValue().equals("USD"))).isTrue();
    }

    @Test
    void shouldBoundInvalidCurrencyToUnknown() {
        Tags tags = MetricTagPolicy.paymentProcessed("SUCCESS", "ZZZ", "CARD");
        assertThat(tags.stream().anyMatch(t -> t.getKey().equals("currency") && t.getValue().equals("UNKNOWN"))).isTrue();
    }

    @Test
    void shouldCreateTicketIssuedTags() {
        Tags tags = MetricTagPolicy.ticketIssued("PAYMENT_COMPLETED");
        assertThat(tags.stream().anyMatch(t -> t.getKey().equals("source") && t.getValue().equals("PAYMENT_COMPLETED"))).isTrue();
    }

    @Test
    void shouldCreateOutboxPublishTags() {
        Tags tags = MetricTagPolicy.outboxPublish("reservation-service", "ReservationHeldEvent", "SUCCESS");
        assertThat(tags.stream().anyMatch(t -> t.getKey().equals("service"))).isTrue();
        assertThat(tags.stream().anyMatch(t -> t.getKey().equals("event_type"))).isTrue();
        assertThat(tags.stream().anyMatch(t -> t.getKey().equals("outcome"))).isTrue();
    }

    @Test
    void shouldCreateOutboxRetryTags() {
        Tags tags = MetricTagPolicy.outboxRetry("payment-service", "PaymentCompleted");
        assertThat(tags.stream().anyMatch(t -> t.getKey().equals("service"))).isTrue();
        assertThat(tags.stream().anyMatch(t -> t.getKey().equals("event_type"))).isTrue();
    }

    @Test
    void shouldValidateTagsObject() {
        Tags good = Tags.of("status", "SUCCESS", "reason", "ALREADY_HELD");
        // should not throw
        MetricTagPolicy.validateTags(good);

        Tags bad = Tags.of("userId", "123", "status", "SUCCESS");
        assertThatThrownBy(() -> MetricTagPolicy.validateTags(bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSanitizeUriTagWithUuidToUnknown() {
        String sanitized = MetricTagPolicy.sanitizeUriTag("/api/reservations/123e4567-e89b-12d3-a456-426614174000");
        assertThat(sanitized).isEqualTo("UNKNOWN");
    }

    @Test
    void shouldAllowKnownTemplates() {
        assertThat(MetricTagPolicy.sanitizeUriTag("/api/reservations")).isEqualTo("/api/reservations");
        assertThat(MetricTagPolicy.sanitizeUriTag("/actuator/prometheus")).isEqualTo("/actuator/prometheus");
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> MetricTagPolicy.validateTagKey("uri"));
    }

    @Test
    void shouldRejectRawUrlInTagKey() {
        assertThatThrownBy(() -> MetricTagPolicy.validateTagKey("rawUrl"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldCreateBoundedDeadLetterTags() {
        Tags tags = MetricTagPolicy.outboxDeadLetter("ticket-service", "TicketIssued");

        assertThat(tags).anySatisfy(tag -> {
            assertThat(tag.getKey()).isEqualTo("service");
            assertThat(tag.getValue()).isEqualTo("ticket-service");
        });
        assertThat(tags).anySatisfy(tag -> {
            assertThat(tag.getKey()).isEqualTo("event_type");
            assertThat(tag.getValue()).isEqualTo("TicketIssued");
        });
    }
}
