# TASK-P05-003: Reservation Service Client, Stripe Payment Gateway Adapter & Payment Service Layer

## 1. Task Metadata
- **Task ID:** `TASK-P05-003`
- **Git Branch:** `feat/p05-003-reservation-client-stripe-gateway-and-service`
- **Target Module:** `backend/services/payment-service`
- **Phase:** `Phase 05 - Payment & Stripe Service`
- **Related Specs:** `.ai/architecture/02-microservices-spec.md` (Section 7), `.ai/architecture/03-database-models.md` (Section 2.5), `.ai/architecture/06-api-contracts.md` (Section 2.5)
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`, `.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the synchronous `ReservationServiceClient` for inter-service communication with `reservation-service`, the `StripePaymentGateway` adapter wrapping the Stripe Java SDK for PaymentIntent creation, and the core transactional `PaymentService` business logic. This task enforces reservation validation (ensuring status `PENDING` and active non-expired hold), idempotency deduplication, server-side Stripe PaymentIntent creation, and initial `Payment` entity persistence in `INITIATED` status.

### Critical Invariants to Enforce:
- [ ] **Synchronous Inter-Service Communication via Eureka & LoadBalancer:** Calls to `reservation-service` must resolve through Eureka service discovery using a `@LoadBalanced RestClient.Builder` targeting `http://reservation-service`. Declare a `@Primary` plain `RestClient.Builder` to prevent Eureka registration conflict.
- [ ] **Reservation Validation Guard:** Validate that the target reservation exists, status is `PENDING`, `totalAmount > 0`, and the 15-minute hold has not expired (`expiresAt > Instant.now()`). Reject expired reservations with `ValidationException("Reservation hold has expired", ErrorCode.RESERVATION_EXPIRED)`.
- [ ] **Single Payment Pipeline Invariant:** Verify if a payment already exists for the given `reservationId`. If the existing payment has status `SUCCESS`, reject with `ConflictException("Reservation is already paid", ErrorCode.PAYMENT_ALREADY_PROCESSED)`.
- [ ] **Idempotency Guarantee:** If a payment with the given `idempotencyKey` already exists:
  - If `reservationId` matches, return the existing `PaymentIntentResponse` (or re-retrieve intent client secret).
  - If parameters conflict, throw `ConflictException("Idempotency key reused with different parameters", ErrorCode.CONFLICT)`.
- [ ] **ADR-001 (Hybrid Guest/Customer):** If `userId` is present on the reservation, attach it to `Payment` and ensure authenticated caller owns the reservation; for unauthenticated guest reservations, allow checkout using the reservation's `customerEmail`.
- [ ] **Stripe SDK Currency Conversion:** Stripe expects amounts in smallest currency units (e.g. cents for USD: `amount.multiply(BigDecimal.valueOf(100)).longValueExact()`).
- [ ] **Resilience:** `ReservationServiceClient` uses Resilience4j Circuit Breaker and forwards `X-Correlation-Id`. Remote client outage fails fast with `ReservationClientUnavailableException` (HTTP 503, `ErrorCode.INTERNAL_SERVER_ERROR`).

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/config/RestClientConfig.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/config/StripeConfig.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/client/ReservationServiceClient.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/client/impl/ReservationServiceClientImpl.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/client/dto/ReservationClientResponse.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/client/dto/SeatHoldClientDto.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/client/exception/ReservationClientUnavailableException.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/gateway/StripePaymentGateway.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/gateway/impl/StripePaymentGatewayImpl.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/gateway/dto/StripeIntentResult.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/service/PaymentService.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/service/impl/PaymentServiceImpl.java`
- `[NEW]` `backend/services/payment-service/src/test/java/com/seatflow/payment/client/ReservationServiceClientTest.java`
- `[NEW]` `backend/services/payment-service/src/test/java/com/seatflow/payment/gateway/StripePaymentGatewayTest.java`
- `[NEW]` `backend/services/payment-service/src/test/java/com/seatflow/payment/service/PaymentServiceImplTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 RestClient & Stripe Configuration

#### `RestClientConfig.java` in `com.seatflow.payment.config`:
```java
package com.seatflow.payment.config;

import com.seatflow.common.observability.context.CorrelationContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    @LoadBalanced
    public RestClient.Builder reservationServiceLoadBalancedBuilder() {
        return RestClient.builder();
    }

    @Bean
    public RestClient reservationRestClient(
            @LoadBalanced RestClient.Builder reservationServiceLoadBalancedBuilder,
            @Value("${reservation-service.base-url:http://reservation-service}") String baseUrl) {

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        return reservationServiceLoadBalancedBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    CorrelationContext.getCorrelationId().ifPresent(corrId ->
                            request.getHeaders().set("X-Correlation-Id", corrId));
                    return execution.execute(request, body);
                })
                .build();
    }
}
```

#### `StripeConfig.java` in `com.seatflow.payment.config`:
```java
package com.seatflow.payment.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
public class StripeConfig {

    @Value("${stripe.api-key:sk_test_dummy}")
    private String apiKey;

    @Value("${stripe.webhook-secret:whsec_dummy}")
    private String webhookSecret;

    @PostConstruct
    public void init() {
        Stripe.apiKey = this.apiKey;
    }
}
```

---

### 4.2 Reservation Client DTOs and Contracts

```java
package com.seatflow.payment.client.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationClientResponse(
    UUID id,
    UUID eventId,
    UUID userId,
    String customerEmail,
    String status,
    Instant expiresAt,
    BigDecimal totalAmount,
    Integer seatCount,
    List<SeatHoldClientDto> seats,
    Instant createdAt
) {}

public record SeatHoldClientDto(
    UUID id,
    UUID seatId,
    String status,
    BigDecimal price
) {}
```

#### `ReservationServiceClient.java`:
```java
package com.seatflow.payment.client;

import com.seatflow.payment.client.dto.ReservationClientResponse;

import java.util.UUID;

public interface ReservationServiceClient {
    ReservationClientResponse getReservation(UUID reservationId);
}
```

#### `ReservationServiceClientImpl.java`:
```java
package com.seatflow.payment.client.impl;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.payment.client.ReservationServiceClient;
import com.seatflow.payment.client.dto.ReservationClientResponse;
import com.seatflow.payment.client.exception.ReservationClientUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReservationServiceClientImpl implements ReservationServiceClient {

    private final RestClient reservationRestClient;

    @Override
    @CircuitBreaker(name = "reservationService", fallbackMethod = "getReservationFallback")
    public ReservationClientResponse getReservation(UUID reservationId) {
        log.debug("Fetching reservation details from reservation-service for reservationId={}", reservationId);

        ReservationClientResponse response = reservationRestClient.get()
                .uri("/api/reservations/{reservationId}", reservationId)
                .retrieve()
                .onStatus(status -> status.value() == 404, (req, res) -> {
                    throw new ResourceNotFoundException("Reservation", reservationId);
                })
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new ReservationClientUnavailableException(
                            "Failed to retrieve reservation details. Status: " + res.getStatusCode());
                })
                .body(ReservationClientResponse.class);

        if (response == null) {
            throw new ReservationClientUnavailableException("Empty response from reservation-service for reservationId=" + reservationId);
        }

        return response;
    }

    public ReservationClientResponse getReservationFallback(UUID reservationId, Throwable t) {
        if (t instanceof ResourceNotFoundException rne) throw rne;
        if (t instanceof ValidationException ve) throw ve;
        if (t instanceof ReservationClientUnavailableException rcue) throw rcue;
        log.error("Circuit breaker triggered for reservation-service call on reservationId={}", reservationId, t);
        throw new ReservationClientUnavailableException("Reservation service is temporarily unavailable", t);
    }
}
```

---

### 4.3 Stripe Payment Gateway Contract

```java
package com.seatflow.payment.gateway.dto;

public record StripeIntentResult(
    String paymentIntentId,
    String clientSecret,
    String status
) {}
```

#### `StripePaymentGateway.java`:
```java
package com.seatflow.payment.gateway;

import com.seatflow.payment.gateway.dto.StripeIntentResult;

import java.math.BigDecimal;
import java.util.Map;

public interface StripePaymentGateway {
    StripeIntentResult createPaymentIntent(
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            Map<String, String> metadata,
            String customerEmail
    );
}
```

#### `StripePaymentGatewayImpl.java`:
```java
package com.seatflow.payment.gateway.impl;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.payment.config.StripeConfig;
import com.seatflow.payment.gateway.StripePaymentGateway;
import com.seatflow.payment.gateway.dto.StripeIntentResult;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class StripePaymentGatewayImpl implements StripePaymentGateway {

    private final StripeConfig stripeConfig;

    @Override
    public StripeIntentResult createPaymentIntent(
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            Map<String, String> metadata,
            String customerEmail) {

        try {
            long amountInCents = amount.multiply(BigDecimal.valueOf(100)).longValueExact();

            PaymentIntentCreateParams.Builder paramsBuilder = PaymentIntentCreateParams.builder()
                    .setAmount(amountInCents)
                    .setCurrency(currency.toLowerCase())
                    .putAllMetadata(metadata)
                    .setAutomaticPaymentMethods(
                            PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                    .setEnabled(true)
                                    .build()
                    );

            if (customerEmail != null && !customerEmail.isBlank()) {
                paramsBuilder.setReceiptEmail(customerEmail);
            }

            RequestOptions requestOptions = RequestOptions.builder()
                    .setApiKey(stripeConfig.getApiKey())
                    .setIdempotencyKey(idempotencyKey)
                    .build();

            PaymentIntent paymentIntent = PaymentIntent.create(paramsBuilder.build(), requestOptions);
            log.info("Stripe PaymentIntent created. paymentIntentId={}, status={}",
                    paymentIntent.getId(), paymentIntent.getStatus());

            return new StripeIntentResult(
                    paymentIntent.getId(),
                    paymentIntent.getClientSecret(),
                    paymentIntent.getStatus()
            );

        } catch (StripeException ex) {
            log.error("Stripe API exception while creating PaymentIntent. idempotencyKey={}", idempotencyKey, ex);
            throw new BusinessException("Payment gateway failure: " + ex.getUserMessage(), ErrorCode.PAYMENT_FAILED, 502);
        }
    }
}
```

---

### 4.4 Payment Service Interface & Implementation Contract

#### `PaymentService.java`:
```java
package com.seatflow.payment.service;

import com.seatflow.payment.web.dto.request.CreatePaymentIntentRequest;
import com.seatflow.payment.web.dto.response.PaymentIntentResponse;
import com.seatflow.payment.web.dto.response.PaymentResponse;

import java.util.UUID;

public interface PaymentService {

    PaymentIntentResponse createPaymentIntent(CreatePaymentIntentRequest request, UUID authenticatedUserId);

    PaymentResponse getPaymentById(UUID paymentId, UUID authenticatedUserId, boolean isAdmin);

    PaymentResponse getPaymentByReservationId(UUID reservationId, UUID authenticatedUserId, boolean isAdmin);
}
```

#### `PaymentServiceImpl.java`:
```java
package com.seatflow.payment.service.impl;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.security.SecurityRoles;
import com.seatflow.common.security.context.UserContext;
import com.seatflow.payment.client.ReservationServiceClient;
import com.seatflow.payment.client.dto.ReservationClientResponse;
import com.seatflow.payment.gateway.StripePaymentGateway;
import com.seatflow.payment.gateway.dto.StripeIntentResult;
import com.seatflow.payment.mapper.PaymentMapper;
import com.seatflow.payment.model.entity.Payment;
import com.seatflow.payment.model.enums.PaymentStatus;
import com.seatflow.payment.repository.PaymentRepository;
import com.seatflow.payment.service.PaymentService;
import com.seatflow.payment.web.dto.request.CreatePaymentIntentRequest;
import com.seatflow.payment.web.dto.response.PaymentIntentResponse;
import com.seatflow.payment.web.dto.response.PaymentResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final ReservationServiceClient reservationServiceClient;
    private final StripePaymentGateway stripePaymentGateway;
    private final PaymentMapper paymentMapper;
    private final MeterRegistry meterRegistry;

    @Override
    @Transactional
    public PaymentIntentResponse createPaymentIntent(CreatePaymentIntentRequest request, UUID authenticatedUserId) {
        Timer.Sample timer = Timer.start(meterRegistry);
        try {
            log.info("Processing PaymentIntent creation. reservationId={}, authenticatedUserId={}, idempotencyKey={}",
                    request.reservationId(), authenticatedUserId, request.idempotencyKey());

            // 1. Check idempotency on client idempotency key
            Optional<Payment> existingIdempotentPayment = paymentRepository.findByIdempotencyKey(request.idempotencyKey());
            if (existingIdempotentPayment.isPresent()) {
                Payment existing = existingIdempotentPayment.get();
                if (existing.getReservationId().equals(request.reservationId())) {
                    log.info("Idempotent replay for paymentId={}, idempotencyKey={}", existing.getId(), request.idempotencyKey());
                    return paymentMapper.toIntentResponse(existing, null);
                } else {
                    throw new ConflictException("Idempotency key reused with different parameters", ErrorCode.CONFLICT);
                }
            }

            // 2. Check if a payment pipeline already exists for this reservationId
            Optional<Payment> existingResPayment = paymentRepository.findByReservationId(request.reservationId());
            if (existingResPayment.isPresent()) {
                Payment payment = existingResPayment.get();
                if (payment.getStatus() == PaymentStatus.SUCCESS) {
                    throw new ConflictException("Reservation is already paid", ErrorCode.PAYMENT_ALREADY_PROCESSED);
                }
            }

            // 3. Fetch and validate reservation from reservation-service
            ReservationClientResponse reservation = reservationServiceClient.getReservation(request.reservationId());

            if (!"PENDING".equalsIgnoreCase(reservation.status())) {
                throw new ValidationException("Cannot process payment for reservation with status: " + reservation.status(),
                        ErrorCode.INVALID_REQUEST);
            }

            if (reservation.expiresAt() == null || reservation.expiresAt().isBefore(Instant.now())) {
                throw new ValidationException("Reservation hold has expired", ErrorCode.RESERVATION_EXPIRED);
            }

            // 4. Authorization check for registered users
            if (reservation.userId() != null && authenticatedUserId != null && !reservation.userId().equals(authenticatedUserId)) {
                if (!UserContext.hasRole(SecurityRoles.ROLE_ADMIN)) {
                    throw new ResourceNotFoundException("Reservation", request.reservationId());
                }
            }

            // 5. Create Stripe PaymentIntent via Gateway
            Map<String, String> metadata = new HashMap<>();
            metadata.put("reservationId", reservation.id().toString());
            metadata.put("eventId", reservation.eventId().toString());
            metadata.put("customerEmail", reservation.customerEmail());
            if (reservation.userId() != null) {
                metadata.put("userId", reservation.userId().toString());
            }

            StripeIntentResult stripeResult = stripePaymentGateway.createPaymentIntent(
                    reservation.totalAmount(),
                    "USD",
                    request.idempotencyKey(),
                    metadata,
                    reservation.customerEmail()
            );

            // 6. Persist Payment entity in INITIATED status
            Payment payment = Payment.builder()
                    .reservationId(reservation.id())
                    .userId(reservation.userId() != null ? reservation.userId() : authenticatedUserId)
                    .customerEmail(reservation.customerEmail())
                    .eventId(reservation.eventId())
                    .stripePaymentIntentId(stripeResult.paymentIntentId())
                    .idempotencyKey(request.idempotencyKey())
                    .amount(reservation.totalAmount())
                    .currency("USD")
                    .status(PaymentStatus.INITIATED)
                    .build();

            Payment savedPayment = paymentRepository.saveAndFlush(payment);

            meterRegistry.counter("seatflow.payments.intent.created.total", "status", "INITIATED").increment();
            log.info("Payment entity persisted in INITIATED status. paymentId={}, stripePaymentIntentId={}, amount={}",
                    savedPayment.getId(), savedPayment.getStripePaymentIntentId(), savedPayment.getAmount());

            return paymentMapper.toIntentResponse(savedPayment, stripeResult.clientSecret());

        } catch (DataIntegrityViolationException dive) {
            log.warn("Database integrity violation during payment intent creation. reservationId={}", request.reservationId(), dive);
            meterRegistry.counter("seatflow.payments.conflicts.total", "reason", "DB_UNIQUE_VIOLATION").increment();
            throw new ConflictException("Payment pipeline already initiated for this reservation", ErrorCode.CONFLICT);
        } finally {
            timer.stop(meterRegistry.timer("seatflow.payments.intent.duration"));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getPaymentById(UUID paymentId, UUID authenticatedUserId, boolean isAdmin) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        if (!isAdmin && payment.getUserId() != null) {
            if (authenticatedUserId == null || !payment.getUserId().equals(authenticatedUserId)) {
                throw new ResourceNotFoundException("Payment", paymentId);
            }
        }

        return paymentMapper.toResponse(payment);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByReservationId(UUID reservationId, UUID authenticatedUserId, boolean isAdmin) {
        Payment payment = paymentRepository.findByReservationId(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment for reservation", reservationId));

        if (!isAdmin && payment.getUserId() != null) {
            if (authenticatedUserId == null || !payment.getUserId().equals(authenticatedUserId)) {
                throw new ResourceNotFoundException("Payment for reservation", reservationId);
            }
        }

        return paymentMapper.toResponse(payment);
    }
}
```

---

### 4.5 Unit Tests Contract
- `ReservationServiceClientTest`: Mockito unit tests verifying:
  - Successful reservation retrieval.
  - 404 response mapped to `ResourceNotFoundException`.
  - Remote 500 error mapped to `ReservationClientUnavailableException`.
  - Circuit breaker fallback throwing `ReservationClientUnavailableException`.
- `StripePaymentGatewayTest`: Unit test verifying amount conversion to cents, parameter builder, metadata construction, and error mapping using Mockito `mockStatic(PaymentIntent.class)` or gateway contract verification.
- `PaymentServiceImplTest`: Mockito unit tests verifying:
  - Rejection of expired reservations with `ValidationException(ErrorCode.RESERVATION_EXPIRED)`.
  - Rejection of non-PENDING reservations with `ValidationException(ErrorCode.INVALID_REQUEST)`.
  - Rejection of already paid reservations with `ConflictException(ErrorCode.PAYMENT_ALREADY_PROCESSED)`.
  - Idempotency replay with matching parameters and rejection with conflicting parameters.
  - Ownership authorization check preventing unauthorized user access.
  - Successful Payment entity persistence in `INITIATED` status with Stripe PaymentIntent ID and client secret return.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p05-003-reservation-client-stripe-gateway-and-service` from `develop`.
2. Implement `RestClientConfig` and `StripeConfig`.
3. Implement `ReservationServiceClient`, client DTOs, `ReservationClientUnavailableException`, and `ReservationServiceClientImpl` with Resilience4j circuit breaker.
4. Implement `StripePaymentGateway`, `StripeIntentResult`, and `StripePaymentGatewayImpl`.
5. Implement `PaymentService` and `PaymentServiceImpl` enforcing idempotency, reservation expiration check, and `INITIATED` status persistence.
6. Write Mockito unit tests `ReservationServiceClientTest`, `StripePaymentGatewayTest`, and `PaymentServiceImplTest`.
7. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/payment-service -Dtest=ReservationServiceClientTest,StripePaymentGatewayTest,PaymentServiceImplTest
```

- [ ] `ReservationServiceClient` communicates via Eureka + LoadBalancer and handles circuit breaker fallbacks.
- [ ] Stripe PaymentIntent creates intents in test mode with accurate cent conversion and metadata.
- [ ] Expired, non-pending, or already-paid reservations are rejected with appropriate error codes.
- [ ] Initial payment is persisted with status `INITIATED`.
- [ ] Task file is moved to `.ai/tasks/completed/phase-05-payment-service/003-reservation-client-stripe-gateway-and-service.md` when complete.
