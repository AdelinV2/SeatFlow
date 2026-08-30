package com.seatflow.payment.client.impl;

import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.payment.client.ReservationServiceClient;
import com.seatflow.payment.client.dto.ReservationClientResponse;
import com.seatflow.payment.client.exception.ReservationClientUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

@Slf4j
@Component
public class ReservationServiceClientImpl implements ReservationServiceClient {

    private static final String CIRCUIT_BREAKER_NAME = "reservationService";
    private static final String RESERVATION_SERVICE_BASE_URL = "http://reservation-service";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient.Builder loadBalancedBuilder;
    private final CircuitBreaker circuitBreaker;
    private final String baseUrl;
    private volatile RestClient restClient;

    public ReservationServiceClientImpl(
            @Qualifier("reservationServiceLoadBalancedBuilder") RestClient.Builder loadBalancedBuilder,
            CircuitBreakerRegistry circuitBreakerRegistry,
            @Value("${reservation-service.base-url:http://reservation-service}") String baseUrl) {
        this.loadBalancedBuilder = loadBalancedBuilder;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        this.baseUrl = normalizeDiscoveryUrl(baseUrl);
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
                .requestInterceptor((request, body, execution) -> {
                    CorrelationContext.getCorrelationId()
                            .ifPresent(id -> request.getHeaders().set("X-Correlation-Id", id));

                    // Registered reservations are protected by the reservation owner/admin check.
                    // Forward the caller's validated JWT so the downstream service can authorize
                    // the same user without exposing or logging the token here.
                    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                    if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
                        request.getHeaders().setBearerAuth(jwtAuthentication.getToken().getTokenValue());
                    }

                    return execution.execute(request, body);
                })
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

    @Override
    public ReservationClientResponse getReservation(UUID reservationId) {
        return getReservation(reservationId, null);
    }

    @Override
    public ReservationClientResponse getReservation(UUID reservationId, String customerEmailProof) {
        try {
            return circuitBreaker.executeSupplier(() -> fetchReservation(reservationId, customerEmailProof));
        } catch (CallNotPermittedException e) {
            log.error("Circuit breaker open for reservation-service call on reservationId={}", reservationId, e);
            throw new ReservationClientUnavailableException("Reservation service is temporarily unavailable", e);
        }
    }

    private ReservationClientResponse fetchReservation(UUID reservationId, String customerEmailProof) {
        log.debug("Fetching reservation details from reservation-service for reservationId={}", reservationId);

        RestClient.RequestHeadersSpec<?> request = client().get()
                .uri("/api/reservations/{reservationId}", reservationId);
        if (customerEmailProof != null && !customerEmailProof.isBlank()) {
            request = request.header("X-Customer-Email", customerEmailProof.trim());
        }

        ReservationClientResponse response = request
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
            throw new ReservationClientUnavailableException(
                    "Empty response from reservation-service for reservationId=" + reservationId);
        }

        return response;
    }
}
