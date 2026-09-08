package com.seatflow.analytics.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seatflow.common.events.EventEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyticsEventDispatcherTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AnalyticsEventDispatcher dispatcher = new AnalyticsEventDispatcher(List.of());

    @Test
    @DisplayName("Unknown event types are not supported and never reach validation")
    void unknownEventTypesAreUnsupported() {
        assertThat(dispatcher.isSupported("UserRegistered")).isFalse();
        assertThat(dispatcher.isSupported("SeatStatusUpdated")).isFalse();
        assertThat(dispatcher.isSupported("PaymentAuthorized")).isFalse();
        assertThat(dispatcher.isSupported("TicketPrinted")).isFalse();
        assertThat(dispatcher.isSupported(null)).isFalse();
        assertThat(dispatcher.isSupported("")).isFalse();
        assertThat(dispatcher.handlerFor("UserRegistered")).isEmpty();
    }

    @Test
    @DisplayName("P14-003 refund/revocation/scan contracts are supported")
    void refundRevocationScanContractsAreSupported() {
        assertThat(dispatcher.isSupported("ReservationRefunded")).isTrue();
        assertThat(dispatcher.isSupported("PaymentRefunded")).isTrue();
        assertThat(dispatcher.isSupported("TicketRevoked")).isTrue();
        assertThat(dispatcher.isSupported("TicketScanned")).isTrue();
        assertThat(dispatcher.isSupported("TicketValidated")).isTrue();
    }

    @Test
    @DisplayName("Verified producer contracts are supported")
    void verifiedContractsAreSupported() {
        assertThat(dispatcher.isSupported("ReservationHeldEvent")).isTrue();
        assertThat(dispatcher.isSupported("ReservationConfirmedEvent")).isTrue();
        assertThat(dispatcher.isSupported("ReservationExpiredEvent")).isTrue();
        assertThat(dispatcher.isSupported("ReservationCancelledEvent")).isTrue();
        assertThat(dispatcher.isSupported("PaymentCompleted")).isTrue();
        assertThat(dispatcher.isSupported("PaymentFailed")).isTrue();
        assertThat(dispatcher.isSupported("TicketIssued")).isTrue();
        assertThat(dispatcher.isSupported("EVENT_CREATED")).isTrue();
        assertThat(dispatcher.isSupported("EVENT_PUBLISHED")).isTrue();
        assertThat(dispatcher.isSupported("EVENT_CANCELLED")).isTrue();
        assertThat(dispatcher.isSupported("EVENT_COMPLETED")).isTrue();
    }

    @Test
    @DisplayName("Valid ReservationHeldEvent envelope parses with identity retained")
    void validReservationHeldParses() {
        ObjectNode root = envelope("ReservationHeldEvent");
        ObjectNode payload = root.putObject("payload");
        payload.put("reservationId", UUID.randomUUID().toString());
        payload.put("eventSessionId", UUID.randomUUID().toString());
        payload.put("eventId", UUID.randomUUID().toString());
        payload.putArray("seatIds").add(UUID.randomUUID().toString()).add(UUID.randomUUID().toString());

        EventEnvelope<JsonNode> envelope = dispatcher.parseAndValidate(root, "seatflow.reservation.events");

        assertThat(envelope.eventType()).isEqualTo("ReservationHeldEvent");
        assertThat(envelope.payload().path("seatIds")).hasSize(2);
    }

    @Test
    @DisplayName("Valid PaymentCompleted envelope parses with money retained")
    void validPaymentCompletedParses() {
        ObjectNode root = envelope("PaymentCompleted");
        ObjectNode payload = root.putObject("payload");
        payload.put("paymentId", UUID.randomUUID().toString());
        payload.put("reservationId", UUID.randomUUID().toString());
        payload.put("amount", "150.00");
        payload.put("currency", "RON");

        EventEnvelope<JsonNode> envelope = dispatcher.parseAndValidate(root, "seatflow.payment.events");

        assertThat(envelope.eventType()).isEqualTo("PaymentCompleted");
    }

    @Test
    @DisplayName("Missing eventId is a validation error, never silently processed")
    void missingEventIdIsValidationError() {
        ObjectNode root = envelope("PaymentCompleted");
        root.remove("eventId");
        ObjectNode payload = root.putObject("payload");
        payload.put("paymentId", UUID.randomUUID().toString());
        payload.put("reservationId", UUID.randomUUID().toString());
        payload.put("amount", "10.00");
        payload.put("currency", "EUR");

        assertThatThrownBy(() -> dispatcher.parseAndValidate(root, "seatflow.payment.events"))
                .isInstanceOf(AnalyticsEventValidationException.class);
    }

    @Test
    @DisplayName("Missing occurredAt is a validation error")
    void missingOccurredAtIsValidationError() {
        ObjectNode root = envelope("TicketIssued");
        root.remove("occurredAt");
        ObjectNode payload = root.putObject("payload");
        payload.put("ticketId", UUID.randomUUID().toString());
        payload.put("reservationId", UUID.randomUUID().toString());

        assertThatThrownBy(() -> dispatcher.parseAndValidate(root, "seatflow.ticket.events"))
                .isInstanceOf(AnalyticsEventValidationException.class);
    }

    @Test
    @DisplayName("Null payload is a validation error")
    void nullPayloadIsValidationError() {
        ObjectNode root = envelope("TicketIssued");
        root.putNull("payload");

        assertThatThrownBy(() -> dispatcher.parseAndValidate(root, "seatflow.ticket.events"))
                .isInstanceOf(AnalyticsEventValidationException.class);
    }

    @Test
    @DisplayName("Negative money cannot reach fact mutation")
    void negativeMoneyIsValidationError() {
        ObjectNode root = envelope("PaymentCompleted");
        ObjectNode payload = root.putObject("payload");
        payload.put("paymentId", UUID.randomUUID().toString());
        payload.put("reservationId", UUID.randomUUID().toString());
        payload.put("amount", "-5.00");
        payload.put("currency", "RON");

        assertThatThrownBy(() -> dispatcher.parseAndValidate(root, "seatflow.payment.events"))
                .isInstanceOf(AnalyticsEventValidationException.class);
    }

    @Test
    @DisplayName("Lowercase or overlong currency cannot reach fact mutation")
    void invalidCurrencyIsValidationError() {
        for (String currency : List.of("ron", "US", "EURO", "", "R0N")) {
            ObjectNode root = envelope("PaymentCompleted");
            ObjectNode payload = root.putObject("payload");
            payload.put("paymentId", UUID.randomUUID().toString());
            payload.put("reservationId", UUID.randomUUID().toString());
            payload.put("amount", "10.00");
            payload.put("currency", currency);

            assertThatThrownBy(() -> dispatcher.parseAndValidate(root, "seatflow.payment.events"))
                    .isInstanceOf(AnalyticsEventValidationException.class);
        }
    }

    @Test
    @DisplayName("Missing required identity fields are validation errors")
    void missingIdentityFieldsAreValidationErrors() {
        ObjectNode root = envelope("ReservationConfirmedEvent");
        ObjectNode payload = root.putObject("payload");
        payload.put("reservationId", UUID.randomUUID().toString());

        assertThatThrownBy(() -> dispatcher.parseAndValidate(root, "seatflow.reservation.events"))
                .isInstanceOf(AnalyticsEventValidationException.class);
    }

    @Test
    @DisplayName("Duplicate handler registration for the same eventType fails fast at startup")
    void duplicateHandlerRegistrationFailsFast() {
        ProjectionHandler first = stubHandler(Set.of("PaymentCompleted"));
        ProjectionHandler second = stubHandler(Set.of("PaymentCompleted"));

        assertThatThrownBy(() -> new AnalyticsEventDispatcher(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PaymentCompleted");
    }

    @Test
    @DisplayName("Dispatcher exposes the verified contract matrix for audit")
    void dispatcherExposesContractMatrix() {
        assertThat(dispatcher.supportedEventTypes()).containsExactlyInAnyOrder(
                "ReservationHeldEvent", "ReservationConfirmedEvent",
                "ReservationExpiredEvent", "ReservationCancelledEvent", "ReservationRefunded",
                "PaymentCompleted", "PaymentFailed", "PaymentRefunded",
                "TicketIssued", "TicketRevoked", "TicketScanned", "TicketValidated",
                "EVENT_CREATED", "EVENT_PUBLISHED", "EVENT_CANCELLED", "EVENT_COMPLETED");
    }

    private ObjectNode envelope(String eventType) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("eventId", "evt-" + UUID.randomUUID());
        root.put("eventType", eventType);
        root.put("occurredAt", Instant.now().toString());
        root.put("aggregateId", UUID.randomUUID().toString());
        return root;
    }

    private static ProjectionHandler stubHandler(Set<String> types) {
        return new ProjectionHandler() {
            @Override
            public Set<String> eventTypes() {
                return types;
            }

            @Override
            public void project(EventEnvelope<JsonNode> envelope, ConsumerRecordMetadata metadata) {
            }
        };
    }
}
