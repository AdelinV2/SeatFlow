package com.seatflow.payment.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.observability.context.CorrelationContext;
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
            log.info("Duplicate succeeded webhook ignored for paymentId={}, stripePaymentIntentId={}",
                    payment.getId(), paymentIntentId);
            return;
        }

        // Extract tax computed by Stripe Tax (ADR-004). stripe-java 28.0.0 does not expose a typed
        // AmountDetails.getTax() getter, so the value is read from the raw event JSON.
        BigDecimal taxAmount = extractTaxAmount(eventRaw);
        BigDecimal netAmount = payment.getAmount().subtract(taxAmount);

        payment.setStatus(PaymentStatus.SUCCESS);
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
            log.info("Duplicate or late failed webhook ignored for paymentId={}, status={}",
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
        try {
            if (eventRaw != null && eventRaw.has("data")) {
                JsonObject data = eventRaw.getAsJsonObject("data");
                if (data != null && data.has("object")) {
                    JsonObject object = data.getAsJsonObject("object");
                    if (object != null && object.has("amount_details")) {
                        JsonObject amountDetails = object.getAsJsonObject("amount_details");
                        // Stripe represents tax as an object: amount_details.tax.total_tax_amount (integer, in cents).
                        if (amountDetails != null && amountDetails.has("tax")) {
                            JsonElement tax = amountDetails.get("tax");
                            if (tax != null && tax.isJsonObject()) {
                                JsonElement totalTax = tax.getAsJsonObject().get("total_tax_amount");
                                if (totalTax != null && !totalTax.isJsonNull()) {
                                    long taxInCents = totalTax.getAsLong();
                                    return BigDecimal.valueOf(taxInCents)
                                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Unable to extract Stripe Tax amount from webhook event payload", ex);
        }
        return BigDecimal.ZERO;
    }

    private void saveOutboxRecord(String eventType, UUID aggregateId, Object payload) {
        try {
            EventEnvelope<?> envelope = EventEnvelope.of(
                    eventType,
                    aggregateId.toString(),
                    CorrelationContext.getCorrelationId().orElse(UUID.randomUUID().toString()),
                    (com.seatflow.common.events.DomainEvent) payload
            );
            String payloadJson = objectMapper.writeValueAsString(envelope);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(payloadJson)
                    .build();
            outboxEventRepository.save(outboxEvent);
        } catch (Exception ex) {
            log.error("Failed to serialize outbox event payload for payment aggregateId={}, eventType={}",
                    aggregateId, eventType, ex);
            throw new RuntimeException("Failed to persist payment outbox event", ex);
        }
    }
}
