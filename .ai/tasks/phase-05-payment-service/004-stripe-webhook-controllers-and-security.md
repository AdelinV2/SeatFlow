# TASK-P05-004: Stripe Webhook Handler, REST Controllers & Security Configuration

## 1. Task Metadata
- **Task ID:** `TASK-P05-004`
- **Git Branch:** `feat/p05-004-stripe-webhook-controllers-and-security`
- **Target Module:** `backend/services/payment-service`
- **Phase:** `Phase 05 - Payment & Stripe Service`
- **Related Specs:** `.ai/architecture/04-authentication-security.md`, `.ai/architecture/06-api-contracts.md` (Section 2.5), `.ai/architecture/05-messaging-and-outbox.md`
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Expose the HTTP REST endpoints for payment intent creation, payment status queries, and Stripe asynchronous webhook ingestion. Implement cryptographic Stripe signature verification, webhook idempotency handling, payment status transition (`INITIATED` → `SUCCESS` / `FAILED`), and transactional outbox event creation (`PaymentCompletedEvent` / `PaymentFailedEvent`). Configure stateless Spring Security OAuth2 Resource Server integration permitting guest checkouts and webhook processing.

### Critical Invariants to Enforce:
- [ ] **Cryptographic Webhook Signature Verification:** All incoming requests to `POST /api/payments/webhook` must verify the `Stripe-Signature` header against the configured `stripe.webhook-secret` using Stripe SDK `Webhook.constructEvent`. Reject invalid signatures with HTTP 400 (`ValidationException(ErrorCode.UNAUTHORIZED)`).
- [ ] **Webhook Idempotency Guarantee:** If `payment_intent.succeeded` arrives for a payment already in `PaymentStatus.SUCCESS`, safely log INFO and return `200 OK` immediately without re-transitioning or generating duplicate outbox events.
- [ ] **Transactional Outbox Pattern:** Status transitions and domain event writes (`PaymentCompletedEvent` or `PaymentFailedEvent`) MUST be committed to `payments` and `outbox_events` in the **same database transaction**. **Never invoke KafkaTemplate directly inside business controllers or webhook handlers.**
- [ ] **Hybrid Guest Checkout (ADR-001):** `POST /api/payments/intent` is publicly accessible. If an `Authorization: Bearer <JWT>` token is present, resolve `userId` and token claims via `UserContext`; otherwise, process payment for the guest customer.
- [ ] **Server-Side Authorization:** `SecurityConfig` permits public access to `/api/payments/intent`, `/api/payments/*`, `/api/payments/webhook`, `/v3/api-docs/**`, `/swagger-ui/**`, and `/actuator/health`. Secured operations extract roles using the auto-configured `JwtRoleConverter` from `common-security`.
- [ ] **Pure HTTP Adapters:** Controllers contain zero direct persistence calls or entity mappings. All input validation uses Jakarta `@Valid`, and methods return `ResponseEntity<T>` with explicit HTTP status codes (`201 CREATED`, `200 OK`).

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/config/SecurityConfig.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/messaging/event/PaymentCompletedEvent.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/messaging/event/PaymentFailedEvent.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/service/StripeWebhookService.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/service/impl/StripeWebhookServiceImpl.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/web/controller/PaymentController.java`
- `[NEW]` `backend/services/payment-service/src/test/java/com/seatflow/payment/service/StripeWebhookServiceImplTest.java`
- `[NEW]` `backend/services/payment-service/src/test/java/com/seatflow/payment/web/controller/PaymentControllerTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Domain Event Records
Create in `com.seatflow.payment.messaging.event`:

```java
package com.seatflow.payment.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Event published when a payment succeeds")
public record PaymentCompletedEvent(
    UUID paymentId,
    UUID reservationId,
    UUID userId,
    String customerEmail,
    UUID eventId,
    BigDecimal amount,
    String currency,
    String stripePaymentId,
    Instant occurredAt
) implements DomainEvent {}
```

```java
package com.seatflow.payment.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Event published when a payment fails")
public record PaymentFailedEvent(
    UUID paymentId,
    UUID reservationId,
    UUID userId,
    String customerEmail,
    UUID eventId,
    BigDecimal amount,
    String currency,
    String stripePaymentId,
    String failureReason,
    Instant occurredAt
) implements DomainEvent {}
```

---

### 4.2 Security Configuration
`SecurityConfig.java` in `com.seatflow.payment.config`:

```java
package com.seatflow.payment.config;

import com.seatflow.common.security.converter.JwtRoleConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(JwtRoleConverter jwtRoleConverter) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwtRoleConverter);
        return converter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                // Public payment creation & status retrieval (Hybrid Guest Flow - ADR-001)
                .requestMatchers(HttpMethod.POST, "/api/payments/intent").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/payments/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/payments/reservation/*").permitAll()
                // Public Stripe Webhook endpoint
                .requestMatchers(HttpMethod.POST, "/api/payments/webhook").permitAll()
                // Documentation & Actuator
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                // All other routes require authentication
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));

        return http.build();
    }
}
```

---

### 4.3 Stripe Webhook Service Contract

#### `StripeWebhookService.java`:
```java
package com.seatflow.payment.service;

public interface StripeWebhookService {
    void handleWebhookEvent(String payload, String sigHeader);
}
```

#### `StripeWebhookServiceImpl.java`:
```java
package com.seatflow.payment.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        StripeObject stripeObject = dataObjectDeserializer.getObject().orElseGet(() -> {
            try {
                return (StripeObject) dataObjectDeserializer.deserializeUnsafe();
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
            case "payment_intent.succeeded" -> handlePaymentIntentSucceeded(paymentIntent);
            case "payment_intent.payment_failed" -> handlePaymentIntentFailed(paymentIntent);
            default -> log.debug("Unhandled Stripe webhook event type: {}", event.getType());
        }
    }

    private void handlePaymentIntentSucceeded(PaymentIntent paymentIntent) {
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
                payment.getCurrency(),
                payment.getStripePaymentIntentId(),
                Instant.now()
        );

        saveOutboxRecord("PaymentCompleted", payment.getId(), completedEvent);
        meterRegistry.counter("seatflow.payments.completed.total", "status", "SUCCESS").increment();

        log.info("Payment successfully processed and PaymentCompleted outbox event created. paymentId={}, reservationId={}",
                payment.getId(), payment.getReservationId());
    }

    private void handlePaymentIntentFailed(PaymentIntent paymentIntent) {
        String paymentIntentId = paymentIntent.getId();
        String failureMessage = paymentIntent.getLastPaymentError() != null
                ? paymentIntent.getLastPaymentError().getMessage()
                : "Unknown payment error";

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
```

---

### 4.4 REST Controller Contract
`PaymentController.java` in `com.seatflow.payment.web.controller`:

```java
package com.seatflow.payment.web.controller;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.common.security.SecurityRoles;
import com.seatflow.common.security.context.UserContext;
import com.seatflow.payment.service.PaymentService;
import com.seatflow.payment.service.StripeWebhookService;
import com.seatflow.payment.web.dto.request.CreatePaymentIntentRequest;
import com.seatflow.payment.web.dto.response.PaymentIntentResponse;
import com.seatflow.payment.web.dto.response.PaymentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payments", description = "Stripe Payment Intent and Webhook processing APIs")
public class PaymentController {

    private final PaymentService paymentService;
    private final StripeWebhookService stripeWebhookService;

    @PostMapping("/intent")
    @Operation(
        summary = "Create Stripe PaymentIntent for a pending reservation",
        description = "Initializes a payment intent in Stripe for an active, non-expired seat reservation. Supports both authenticated customers and guests (ADR-001)."
    )
    @ApiResponse(responseCode = "201", description = "PaymentIntent created successfully",
        content = @Content(schema = @Schema(implementation = PaymentIntentResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error, reservation expired, or invalid status",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Reservation not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Reservation is already paid or idempotency key conflict",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "502", description = "Stripe gateway failure",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<PaymentIntentResponse> createPaymentIntent(
            @Valid @RequestBody CreatePaymentIntentRequest request) {

        UUID authenticatedUserId = UserContext.getCurrentUserId()
                .map(UUID::fromString)
                .orElse(null);

        PaymentIntentResponse response = paymentService.createPaymentIntent(request, authenticatedUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{paymentId}")
    @Operation(
        summary = "Get payment details by payment ID",
        description = "Retrieves payment status, transaction amounts, and Stripe identifiers."
    )
    @ApiResponse(responseCode = "200", description = "Payment found",
        content = @Content(schema = @Schema(implementation = PaymentResponse.class)))
    @ApiResponse(responseCode = "404", description = "Payment not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable UUID paymentId) {
        UUID authenticatedUserId = UserContext.getCurrentUserId()
                .map(UUID::fromString)
                .orElse(null);
        boolean isAdmin = UserContext.hasRole(SecurityRoles.ROLE_ADMIN);

        PaymentResponse response = paymentService.getPaymentById(paymentId, authenticatedUserId, isAdmin);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/reservation/{reservationId}")
    @Operation(
        summary = "Get payment details by reservation ID",
        description = "Retrieves payment record associated with a specific reservation."
    )
    @ApiResponse(responseCode = "200", description = "Payment found",
        content = @Content(schema = @Schema(implementation = PaymentResponse.class)))
    @ApiResponse(responseCode = "404", description = "Payment for reservation not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<PaymentResponse> getPaymentByReservation(@PathVariable UUID reservationId) {
        UUID authenticatedUserId = UserContext.getCurrentUserId()
                .map(UUID::fromString)
                .orElse(null);
        boolean isAdmin = UserContext.hasRole(SecurityRoles.ROLE_ADMIN);

        PaymentResponse response = paymentService.getPaymentByReservationId(reservationId, authenticatedUserId, isAdmin);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/webhook")
    @Operation(
        summary = "Stripe webhook event handler",
        description = "Receives signed asynchronous event notifications from Stripe (payment_intent.succeeded, payment_intent.payment_failed)."
    )
    @ApiResponse(responseCode = "200", description = "Webhook event received and processed")
    @ApiResponse(responseCode = "400", description = "Invalid Stripe signature or malformed payload",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<Map<String, Boolean>> handleStripeWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader) {

        if (sigHeader == null || sigHeader.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("received", false));
        }

        stripeWebhookService.handleWebhookEvent(payload, sigHeader);
        return ResponseEntity.ok(Map.of("received", true));
    }
}
```

---

### 4.5 Web Slice Test Contract
- `PaymentControllerTest`: `@WebMvcTest(PaymentController.class)`, `@Import(SecurityConfig.class)`.
  - Asserts `POST /api/payments/intent` returns `201 CREATED` with valid request body.
  - Asserts `POST /api/payments/intent` returns `400 BAD REQUEST` when `reservationId` or `idempotencyKey` is missing.
  - Asserts `GET /api/payments/{id}` returns `200 OK` with `PaymentResponse`.
  - Asserts `POST /api/payments/webhook` with valid signature calls `stripeWebhookService.handleWebhookEvent(...)` and returns `200 OK {"received": true}`.
  - Asserts `POST /api/payments/webhook` without `Stripe-Signature` returns `400 BAD REQUEST`.
- `StripeWebhookServiceImplTest`: Mockito unit tests verifying:
  - Valid `payment_intent.succeeded` transitions payment to `SUCCESS` and commits `PaymentCompletedEvent` to outbox (using `mockStatic(Webhook.class)` to verify signature and event parsing).
  - Duplicate `payment_intent.succeeded` for already `SUCCESS` payment is skipped idempotently without creating extra outbox records.
  - Valid `payment_intent.payment_failed` transitions payment to `FAILED` and commits `PaymentFailedEvent` to outbox.
  - Invalid signature throws `ValidationException(ErrorCode.UNAUTHORIZED)`.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p05-004-stripe-webhook-controllers-and-security` from `develop`.
2. Implement domain event records `PaymentCompletedEvent` and `PaymentFailedEvent`.
3. Configure `SecurityConfig` allowing public intent creation, public webhook endpoint, and actuator.
4. Implement `StripeWebhookService` and `StripeWebhookServiceImpl` with cryptographic signature verification, idempotency skip, and outbox event persistence.
5. Implement `PaymentController` with OpenAPI annotations and response envelopes.
6. Write unit tests in `StripeWebhookServiceImplTest` and controller slice tests in `PaymentControllerTest`.
7. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/payment-service -Dtest=PaymentControllerTest,StripeWebhookServiceImplTest
```

- [ ] `POST /api/payments/intent` creates payment intent and returns client secret.
- [ ] `POST /api/payments/webhook` verifies `Stripe-Signature` and processes events idempotently.
- [ ] Successful payment transitions status to `SUCCESS` and creates `PaymentCompletedEvent` outbox row in the same transaction.
- [ ] Controller slice and webhook service unit tests pass.
- [ ] Task file is moved to `.ai/tasks/completed/phase-05-payment-service/004-stripe-webhook-controllers-and-security.md` when complete.
