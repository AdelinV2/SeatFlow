package com.seatflow.ai.client.impl;

import com.seatflow.ai.client.ReservationServiceClient;
import com.seatflow.ai.client.dto.CreateReservationServiceRequest;
import com.seatflow.ai.client.dto.ReservationServiceReservationDto;
import com.seatflow.ai.client.exception.ReservationServiceUnavailableException;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ConflictException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Eureka + LoadBalancer Reservation Service client for the AI confirmation flow.
 *
 * <p>Conventions mirror the sibling inter-service clients (constitution invariant 10): logical
 * service-name base URL, explicit connect/read timeouts, correlation + Bearer propagation, and a
 * shared {@code reservationService} Resilience4j circuit breaker. The caller's JWT is always
 * propagated; after a {@code 403} the client surfaces a safe error and never retries with a
 * privileged identity. Guest proof headers are never sent. Raw downstream bodies are never
 * included in messages or logs.
 */
@Slf4j
@Component
public class ReservationServiceClientImpl implements ReservationServiceClient {

    static final String CIRCUIT_BREAKER_NAME = "reservationService";
    static final String RESERVATION_SERVICE_BASE_URL = "http://reservation-service";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient.Builder loadBalancedBuilder;
    private final CircuitBreaker circuitBreaker;
    private final String baseUrl;
    private volatile RestClient restClient;

    @Autowired
    public ReservationServiceClientImpl(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedBuilder,
            CircuitBreakerRegistry circuitBreakerRegistry,
            @Value("${reservation-service.base-url:http://reservation-service}") String baseUrl) {
        this.loadBalancedBuilder = loadBalancedBuilder;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        this.baseUrl = normalizeDiscoveryUrl(baseUrl);
        this.restClient = null;
    }

    /** Test-only path: use a prebuilt client (e.g. bound to a mock HTTP server). */
    ReservationServiceClientImpl(RestClient restClient, CircuitBreaker circuitBreaker, String baseUrl) {
        this.loadBalancedBuilder = null;
        this.circuitBreaker = circuitBreaker;
        this.baseUrl = baseUrl;
        this.restClient = restClient;
    }

    @Override
    public ReservationServiceReservationDto getReservation(UUID reservationId, AiRequestContext context) {
        requireContext(context);
        if (reservationId == null) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "A reservationId is required to look up a reservation.");
        }
        try {
            return circuitBreaker.executeSupplier(() -> fetchReservation(reservationId, context));
        } catch (CallNotPermittedException e) {
            log.warn("reservation-service circuit open for getReservation: reservationId={}", reservationId);
            throw new ReservationServiceUnavailableException(AiToolError.DOWNSTREAM_UNAVAILABLE,
                    "Reservation lookup is temporarily unavailable. Please try again shortly.", e);
        }
    }

    @Override
    public ReservationServiceReservationDto createReservation(
            UUID eventSessionId,
            List<UUID> seatIds,
            List<BigDecimal> seatPrices,
            String idempotencyKey,
            AiRequestContext context) {
        requireContext(context);
        CreateReservationServiceRequest request =
                new CreateReservationServiceRequest(eventSessionId, seatIds, seatPrices, idempotencyKey);
        try {
            return circuitBreaker.executeSupplier(() -> postReservation(request, context));
        } catch (CallNotPermittedException e) {
            log.warn("reservation-service circuit open for createReservation: eventSessionId={}", eventSessionId);
            throw new ReservationServiceUnavailableException(AiToolError.DOWNSTREAM_UNAVAILABLE,
                    "Reservation creation is temporarily unavailable. Please try again shortly.", e);
        }
    }

    private ReservationServiceReservationDto fetchReservation(UUID reservationId, AiRequestContext context) {
        log.debug("Fetching reservation from reservation-service: reservationId={}", reservationId);
        try {
            ReservationServiceReservationDto response = client().get()
                    .uri("/api/reservations/{reservationId}", reservationId)
                    .headers(headers -> applyPropagation(headers, context))
                    .retrieve()
                    .onStatus(status -> status.value() == 400, (req, res) -> {
                        throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                                "The reservation lookup was rejected.");
                    })
                    .onStatus(status -> status.value() == 401, (req, res) -> {
                        throw new AiToolException(AiToolError.UNAUTHENTICATED,
                                "Authentication is required to look up a reservation.");
                    })
                    .onStatus(status -> status.value() == 403, (req, res) -> {
                        // Ownership-safe: surface a safe error with the caller identity.
                        // Never retry with a privileged identity.
                        throw new AiToolException(AiToolError.FORBIDDEN,
                                "You are not allowed to view this reservation with these credentials.");
                    })
                    .onStatus(status -> status.value() == 404, (req, res) -> {
                        throw new AiToolException(AiToolError.NOT_FOUND,
                                "Reservation not found.");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new ReservationServiceUnavailableException(AiToolError.DOWNSTREAM_UNAVAILABLE,
                                "Reservation lookup is temporarily unavailable. Please try again shortly.");
                    })
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new ReservationServiceUnavailableException(AiToolError.UNEXPECTED_TOOL_FAILURE,
                                "Reservation lookup failed unexpectedly.");
                    })
                    .body(ReservationServiceReservationDto.class);
            if (response == null || response.id() == null) {
                throw new ReservationServiceUnavailableException(AiToolError.UNEXPECTED_TOOL_FAILURE,
                        "Reservation lookup returned an incomplete response.");
            }
            return response;
        } catch (ResourceAccessException e) {
            throw mapTransportFailure("Reservation lookup", e);
        }
    }

    private ReservationServiceReservationDto postReservation(
            CreateReservationServiceRequest request, AiRequestContext context) {
        log.info("Creating confirmed reservation hold: eventSessionId={}, seats={}, idempotencyPresent={}",
                request.eventSessionId(), request.seatIds().size(), true);
        try {
            ReservationServiceReservationDto response = client().post()
                    .uri("/api/reservations")
                    .headers(headers -> applyPropagation(headers, context))
                    .body(request)
                    .retrieve()
                    .onStatus(status -> status.value() == 400, (req, res) -> {
                        throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                                "The reservation request was rejected. A fresh proposal may be required.");
                    })
                    .onStatus(status -> status.value() == 401, (req, res) -> {
                        throw new AiToolException(AiToolError.UNAUTHENTICATED,
                                "Authentication is required to create a reservation.");
                    })
                    .onStatus(status -> status.value() == 403, (req, res) -> {
                        throw new AiToolException(AiToolError.FORBIDDEN,
                                "You are not allowed to create this reservation with these credentials.");
                    })
                    .onStatus(status -> status.value() == 404, (req, res) -> {
                        throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                                "The reservation session is no longer available. A fresh proposal is required.");
                    })
                    .onStatus(status -> status.value() == 409, (req, res) -> {
                        throw new ConflictException(
                                "One or more seats are already held or sold",
                                ErrorCode.SEAT_ALREADY_RESERVED);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new ReservationServiceUnavailableException(AiToolError.DOWNSTREAM_UNAVAILABLE,
                                "Reservation creation is temporarily unavailable. Please try again shortly.");
                    })
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new ReservationServiceUnavailableException(AiToolError.UNEXPECTED_TOOL_FAILURE,
                                "Reservation creation failed unexpectedly.");
                    })
                    .body(ReservationServiceReservationDto.class);
            if (response == null || response.id() == null || response.expiresAt() == null) {
                throw new ReservationServiceUnavailableException(AiToolError.UNEXPECTED_TOOL_FAILURE,
                        "Reservation creation returned an incomplete response.");
            }
            return response;
        } catch (ResourceAccessException e) {
            // A POST timeout is ambiguous: the hold may or may not exist. Callers map this to
            // RESERVATION_RESULT_UNKNOWN_RETRY_SAFE and reconcile with the same idempotency key.
            throw mapTransportFailure("Reservation creation", e);
        }
    }

    private void requireContext(AiRequestContext context) {
        if (context == null || !context.isAuthenticated()) {
            throw new AiToolException(AiToolError.UNAUTHENTICATED,
                    "Authentication is required to use the AI reservation tools.");
        }
    }

    private void applyPropagation(HttpHeaders headers, AiRequestContext context) {
        headers.set("X-Correlation-Id", context.correlationId());
        headers.setBearerAuth(context.bearerToken());
    }

    private ReservationServiceUnavailableException mapTransportFailure(
            String operation, ResourceAccessException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                log.warn("{} timed out against reservation-service", operation);
                return new ReservationServiceUnavailableException(AiToolError.DOWNSTREAM_TIMEOUT,
                        operation + " timed out. Please try again shortly.", e);
            }
            cause = cause.getCause();
        }
        log.warn("{} failed against reservation-service: {}", operation, e.getMessage());
        return new ReservationServiceUnavailableException(AiToolError.DOWNSTREAM_UNAVAILABLE,
                operation + " is temporarily unavailable. Please try again shortly.", e);
    }

    private RestClient client() {
        RestClient client = this.restClient;
        if (client == null) {
            synchronized (this) {
                client = this.restClient;
                if (client == null) {
                    this.restClient = client = buildClient();
                }
            }
        }
        return client;
    }

    private RestClient buildClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return loadBalancedBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    private String normalizeDiscoveryUrl(String configuredBaseUrl) {
        if (configuredBaseUrl == null || configuredBaseUrl.isBlank()) {
            return RESERVATION_SERVICE_BASE_URL;
        }
        try {
            URI uri = URI.create(configuredBaseUrl.trim());
            String host = uri.getHost();
            if (host != null && (host.equalsIgnoreCase("localhost")
                    || host.equals("127.0.0.1")
                    || host.equals("::1"))) {
                log.warn("Ignoring direct reservation-service URL {} for the Eureka load-balanced client; using {} instead",
                        configuredBaseUrl, RESERVATION_SERVICE_BASE_URL);
                return RESERVATION_SERVICE_BASE_URL;
            }
            return configuredBaseUrl.trim().replaceAll("/+$", "");
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid reservation-service URL {}; using Eureka service ID {} instead",
                    configuredBaseUrl, RESERVATION_SERVICE_BASE_URL);
            return RESERVATION_SERVICE_BASE_URL;
        }
    }
}
