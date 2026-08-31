package com.seatflow.payment.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.common.observability.tracing.W3cTraceContextPropagator;
import com.seatflow.payment.config.StripeConfig;
import com.seatflow.payment.messaging.event.PaymentCompletedEvent;
import com.seatflow.payment.messaging.event.PaymentFailedEvent;
import com.seatflow.payment.model.entity.OutboxEvent;
import com.seatflow.payment.model.entity.Payment;
import com.seatflow.payment.model.enums.PaymentStatus;
import com.seatflow.payment.repository.OutboxEventRepository;
import com.seatflow.payment.repository.PaymentRepository;
import com.seatflow.payment.service.StripeWebhookService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeError;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StripeWebhookServiceImpl implements StripeWebhookService {

    private final StripeConfig stripeConfig;
    private final PaymentRepository paymentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final W3cTraceContextPropagator w3cTraceContextPropagator;

    @Override
    @Transactional
    public void handleWebhookEvent(String payload, String sigHeader) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, stripeConfig.getWebhookSecret());
        } catch (SignatureVerificationException e) {
            log.warn("Invalid Stripe webhook signature verification failed", e);
            throw new ValidationException("Invalid Stripe signature", ErrorCode.UNAUTHORIZED);
        } catch (Exception e) {
            log.error("Failed to parse Stripe webhook event payload", e);
            throw new ValidationException("Failed to parse Stripe webhook payload", ErrorCode.INVALID_REQUEST);
        }

        log.info("Processing Stripe webhook event. eventType={}, eventId={}", event.getType(), event.getId());

        // The raw JSON is only populated on the Event (populated by Webhook.constructEvent); a nested
        // PaymentIntent obtained via getDataObjectDeserializer() has no lastResponse and getRawJsonObject() is null.
        JsonObject eventRaw = event.getRawJsonObject();

        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        StripeObject stripeObject = dataObjectDeserializer.getObject().orElseGet(() -> {
            try {
                return dataObjectDeserializer.deserializeUnsafe();
            } catch (Exception ex) {
                log.warn("Stripe webhook unsafe deserialization failed for eventId={}", event.getId(), ex);
                return null;
            }
        });

        if (!(stripeObject instanceof PaymentIntent paymentIntent)) {
            log.debug("Stripe event {} object is not a PaymentIntent", event.getType());
            return;
        }

        switch (event.getType()) {
            case "payment_intent.succeeded" -> handlePaymentIntentSucceeded(paymentIntent, eventRaw);
            case "payment_intent.payment_failed" -> handlePaymentIntentFailed(paymentIntent);
            default -> log.debug("Unhandled Stripe webhook event type: {}", event.getType());
        }
    }

    private void handlePaymentIntentSucceeded(PaymentIntent paymentIntent, JsonObject eventRaw) {
        String paymentIntentId = paymentIntent.getId();
        log.info("Handling payment_intent.succeeded for paymentIntentId={}", paymentIntentId);

        Optional<Payment> paymentOpt = paymentRepository.findByStripePaymentIntentId(paymentIntentId);
        if (paymentOpt.isEmpty()) {
            log.error("Received payment_intent.succeeded for unknown paymentIntentId={}", paymentIntentId);
            return;
        }

        Payment payment = paymentOpt.get();

        // Webhook Idempotency Check
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            log.warn("Stripe webhook duplicate ignored. paymentId={}, stripePaymentIntentId={}",
                    payment.getId(), paymentIntentId);
            return;
        }

        // Extract tax computed by Stripe Tax (ADR-004). stripe-java 28.0.0 does not expose a typed
        // AmountDetails.getTax() getter, so the value is read from the raw event JSON.
        BigDecimal taxAmount = extractTaxAmount(eventRaw);
        BigDecimal netAmount = payment.getAmount().subtract(taxAmount);

        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setTaxAmount(taxAmount);
        payment.setNetAmount(netAmount);
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        PaymentCompletedEvent completedEvent = new PaymentCompletedEvent(
                payment.getId(),
                payment.getReservationId(),
                payment.getUserId(),
                payment.getCustomerEmail(),
                payment.getEventId(),
                payment.getAmount(),
                taxAmount,
                netAmount,
                payment.getCurrency(),
                payment.getStripePaymentIntentId(),
                Instant.now()
        );

        saveOutboxRecord("PaymentCompleted", payment.getId(), completedEvent);
        meterRegistry.counter("seatflow.payments.completed.total", "status", "SUCCESS").increment();

        log.info("Payment successfully processed and PaymentCompleted outbox event created. paymentId={}, reservationId={}, taxAmount={}, netAmount={}",
                payment.getId(), payment.getReservationId(), taxAmount, netAmount);
    }

    private void handlePaymentIntentFailed(PaymentIntent paymentIntent) {
        String paymentIntentId = paymentIntent.getId();
        StripeError lastError = paymentIntent.getLastPaymentError();
        String failureMessage = lastError != null ? lastError.getMessage() : "Unknown payment error";

        log.warn("Handling payment_intent.payment_failed for paymentIntentId={}, reason={}",
                paymentIntentId, failureMessage);

        Optional<Payment> paymentOpt = paymentRepository.findByStripePaymentIntentId(paymentIntentId);
        if (paymentOpt.isEmpty()) {
            log.error("Received payment_intent.payment_failed for unknown paymentIntentId={}", paymentIntentId);
            return;
        }

        Payment payment = paymentOpt.get();

        if (payment.getStatus() == PaymentStatus.SUCCESS || payment.getStatus() == PaymentStatus.FAILED) {
            log.warn("Stripe webhook duplicate or late failure ignored. paymentId={}, status={}",
                    payment.getId(), payment.getStatus());
            return;
        }

        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason(failureMessage);
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        PaymentFailedEvent failedEvent = new PaymentFailedEvent(
                payment.getId(),
                payment.getReservationId(),
                payment.getUserId(),
                payment.getCustomerEmail(),
                payment.getEventId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStripePaymentIntentId(),
                failureMessage,
                Instant.now()
        );

        saveOutboxRecord("PaymentFailed", payment.getId(), failedEvent);
        meterRegistry.counter("seatflow.payments.failed.total", "reason", "STRIPE_FAILED").increment();

        log.warn("Payment marked as FAILED and PaymentFailed outbox event created. paymentId={}, reservationId={}",
                payment.getId(), payment.getReservationId());
    }

    private BigDecimal extractTaxAmount(JsonObject eventRaw) {
        if (eventRaw == null) {
            return new BigDecimal("0.00");
        }

        try {
            JsonElement dataElem = eventRaw.get("data");
            if (dataElem != null && dataElem.isJsonObject()) {
                JsonElement objElem = dataElem.getAsJsonObject().get("object");
                if (objElem != null && objElem.isJsonObject()) {
                    JsonElement amountDetailsElem = objElem.getAsJsonObject().get("amount_details");
                    if (amountDetailsElem != null && amountDetailsElem.isJsonObject()) {
                        JsonElement taxElem = amountDetailsElem.getAsJsonObject().get("tax");
                        // Stripe Tax (ADR-004) returns `amount_details.tax` as a JSON array of
                        // line items, each carrying an `amount` (in cents), e.g.
                        // [{"amount":190,"tax_rate":"txr_..."}]. There is no `total_tax_amount` field.
                        if (taxElem != null && taxElem.isJsonArray()) {
                            long totalTaxCents = 0;
                            for (JsonElement item : taxElem.getAsJsonArray()) {
                                if (item.isJsonObject() && item.getAsJsonObject().has("amount")) {
                                    JsonElement amountElem = item.getAsJsonObject().get("amount");
                                    if (amountElem.isJsonPrimitive()) {
                                        totalTaxCents += amountElem.getAsLong();
                                    }
                                }
                            }
                            return BigDecimal.valueOf(totalTaxCents)
                                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Unable to extract Stripe Tax amount from webhook event payload", ex);
        }

        return new BigDecimal("0.00");
    }

    private void saveOutboxRecord(String eventType, UUID aggregateId, Object payload) {
        try {
            EventEnvelope<?> base = EventEnvelope.of(
                    eventType,
                    aggregateId.toString(),
                    CorrelationContext.getCorrelationId().orElse(UUID.randomUUID().toString()),
                    (com.seatflow.common.events.DomainEvent) payload
            );
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            try {
                if (w3cTraceContextPropagator != null) {
                    w3cTraceContextPropagator.inject(headers);
                }
            } catch (Exception ignored) {
            }
            EventEnvelope<?> envelope = base.withHeaders(headers);
            // Store the envelope as a real JSON object in the jsonb column (not a JSON-string scalar).
            JsonNode payloadNode = objectMapper.valueToTree(envelope);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(payloadNode)
                    .build();
            outboxEventRepository.save(outboxEvent);
        } catch (Exception ex) {
            log.error("Failed to serialize outbox event payload for payment aggregateId={}, eventType={}",
                    aggregateId, eventType, ex);
            throw new RuntimeException("Failed to persist payment outbox event", ex);
        }
    }
}
