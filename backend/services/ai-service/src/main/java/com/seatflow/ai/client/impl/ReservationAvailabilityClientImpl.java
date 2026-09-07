package com.seatflow.ai.client.impl;

import com.seatflow.ai.client.ReservationAvailabilityClient;
import com.seatflow.ai.client.dto.SeatAvailabilityClientDto;
import com.seatflow.ai.client.exception.ReservationServiceUnavailableException;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;
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

import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/**
 * Eureka + LoadBalancer Reservation Service client for AI seat availability.
 *
 * <p>Conventions mirror {@link EventServiceClientImpl} (constitution invariant 10): logical
 * service-name base URL, explicit connect/read timeouts, correlation + Bearer propagation, and a
 * Resilience4j circuit breaker. A timeout or outage surfaces {@code DOWNSTREAM_TIMEOUT} /
 * {@code DOWNSTREAM_UNAVAILABLE} so callers fail instead of serving stale availability.
 */
@Slf4j
@Component
public class ReservationAvailabilityClientImpl implements ReservationAvailabilityClient {

    static final String CIRCUIT_BREAKER_NAME = "reservationService";
    static final String RESERVATION_SERVICE_BASE_URL = "http://reservation-service";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient.Builder loadBalancedBuilder;
    private final CircuitBreaker circuitBreaker;
    private final String baseUrl;
    private volatile RestClient restClient;

    @Autowired
    public ReservationAvailabilityClientImpl(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedBuilder,
            CircuitBreakerRegistry circuitBreakerRegistry,
            @Value("${reservation-service.base-url:http://reservation-service}") String baseUrl) {
        this.loadBalancedBuilder = loadBalancedBuilder;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        this.baseUrl = normalizeDiscoveryUrl(baseUrl);
        this.restClient = null;
    }

    /** Test-only path: use a prebuilt client (e.g. bound to a mock HTTP server). */
    ReservationAvailabilityClientImpl(RestClient restClient, CircuitBreaker circuitBreaker, String baseUrl) {
        this.loadBalancedBuilder = null;
        this.circuitBreaker = circuitBreaker;
        this.baseUrl = baseUrl;
        this.restClient = restClient;
    }

    @Override
    public SeatAvailabilityClientDto getSeatAvailability(UUID eventSessionId, AiRequestContext context) {
        requireContext(context);
        if (eventSessionId == null) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "An eventSessionId is required to read seat availability.");
        }
        try {
            return circuitBreaker.executeSupplier(() -> fetchAvailability(eventSessionId, context));
        } catch (CallNotPermittedException e) {
            log.warn("reservation-service circuit open for getSeatAvailability: eventSessionId={}",
                    eventSessionId);
            throw new ReservationServiceUnavailableException(AiToolError.DOWNSTREAM_UNAVAILABLE,
                    "Seat availability is temporarily unavailable. Please try again shortly.", e);
        }
    }

    private SeatAvailabilityClientDto fetchAvailability(UUID eventSessionId, AiRequestContext context) {
        log.debug("Fetching session seat availability from reservation-service: eventSessionId={}",
                eventSessionId);
        try {
            SeatAvailabilityClientDto response = client().get()
                    .uri("/api/event-sessions/{eventSessionId}/seats/availability", eventSessionId)
                    .headers(headers -> applyPropagation(headers, context))
                    .retrieve()
                    .onStatus(status -> status.value() == 400, (req, res) -> {
                        throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                                "The session identifier was rejected by the reservation service.");
                    })
                    .onStatus(status -> status.value() == 401, (req, res) -> {
                        throw new AiToolException(AiToolError.UNAUTHENTICATED,
                                "Authentication is required to read seat availability.");
                    })
                    .onStatus(status -> status.value() == 403, (req, res) -> {
                        throw new AiToolException(AiToolError.FORBIDDEN,
                                "You are not allowed to read seat availability with these credentials.");
                    })
                    .onStatus(status -> status.value() == 404, (req, res) -> {
                        throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                                "Unknown event session: " + eventSessionId);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new ReservationServiceUnavailableException(AiToolError.DOWNSTREAM_UNAVAILABLE,
                                "Seat availability is temporarily unavailable. Please try again shortly.");
                    })
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new ReservationServiceUnavailableException(AiToolError.UNEXPECTED_TOOL_FAILURE,
                                "Seat availability lookup failed unexpectedly.");
                    })
                    .body(SeatAvailabilityClientDto.class);
            if (response == null) {
                throw new ReservationServiceUnavailableException(AiToolError.UNEXPECTED_TOOL_FAILURE,
                        "Seat availability lookup returned an empty response.");
            }
            if (response.seatStatuses() == null) {
                throw new ReservationServiceUnavailableException(AiToolError.UNEXPECTED_TOOL_FAILURE,
                        "Seat availability lookup returned an incomplete response.");
            }
            if (response.eventSessionId() != null && !eventSessionId.equals(response.eventSessionId())) {
                throw new ReservationServiceUnavailableException(AiToolError.UNEXPECTED_TOOL_FAILURE,
                        "Seat availability lookup returned data for a different session.");
            }
            return response;
        } catch (ResourceAccessException e) {
            throw mapTransportFailure("Seat availability lookup", e);
        }
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

    private void requireContext(AiRequestContext context) {
        if (context == null || !context.isAuthenticated()) {
            throw new AiToolException(AiToolError.UNAUTHENTICATED,
                    "Authentication is required to use the AI assistant tools.");
        }
    }

    private void applyPropagation(HttpHeaders headers, AiRequestContext context) {
        headers.set("X-Correlation-Id", context.correlationId());
        headers.setBearerAuth(context.bearerToken());
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
