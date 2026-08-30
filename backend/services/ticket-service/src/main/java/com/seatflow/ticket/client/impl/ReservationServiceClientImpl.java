package com.seatflow.ticket.client.impl;

import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.ticket.client.ReservationServiceClient;
import com.seatflow.ticket.client.dto.ReservationClientResponse;
import com.seatflow.ticket.client.exception.InterServiceClientException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class ReservationServiceClientImpl implements ReservationServiceClient {

    private static final String CIRCUIT_BREAKER_NAME = "reservationService";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient.Builder loadBalancedBuilder;
    private final CircuitBreaker circuitBreaker;
    private final String baseUrl;
    private volatile RestClient restClient;

    public ReservationServiceClientImpl(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedBuilder,
            CircuitBreakerRegistry circuitBreakerRegistry,
            @Value("${reservation-service.base-url:http://reservation-service}") String baseUrl) {
        this.loadBalancedBuilder = loadBalancedBuilder;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        this.baseUrl = baseUrl;
    }

    @Override
    public Optional<ReservationClientResponse> getReservationById(UUID reservationId) {
        return getReservationById(reservationId, null);
    }

    @Override
    public Optional<ReservationClientResponse> getReservationById(UUID reservationId, String customerEmail) {
        try {
            return circuitBreaker.executeSupplier(() -> fetchReservation(reservationId, customerEmail));
        } catch (CallNotPermittedException | RestClientException e) {
            log.warn("reservation-service call failed for reservationId={}: {}", reservationId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ReservationClientResponse> fetchReservation(UUID reservationId, String customerEmail) {
        try {
            RestClient.RequestHeadersSpec<?> request = client().get()
                    .uri("/api/reservations/{reservationId}/internal", reservationId);
            if (customerEmail != null && !customerEmail.isBlank()) {
                request = request.header("X-Customer-Email", customerEmail.trim());
            }

            ReservationClientResponse response = request
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new InterServiceClientException(
                                "reservation-service returned error " + res.getStatusCode() + " for reservationId=" + reservationId);
                    })
                    .body(ReservationClientResponse.class);
            return Optional.ofNullable(response);
        } catch (InterServiceClientException e) {
            log.warn("reservation-service internal endpoint error, trying fallback endpoint for reservationId={}: {}", reservationId, e.getMessage());
            try {
                RestClient.RequestHeadersSpec<?> fallbackRequest = client().get()
                        .uri("/api/reservations/{reservationId}", reservationId);
                if (customerEmail != null && !customerEmail.isBlank()) {
                    fallbackRequest = fallbackRequest.header("X-Customer-Email", customerEmail.trim());
                }
                ReservationClientResponse fallbackResponse = fallbackRequest
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (req, res) -> {
                            throw new InterServiceClientException("Fallback returned " + res.getStatusCode());
                        })
                        .body(ReservationClientResponse.class);
                return Optional.ofNullable(fallbackResponse);
            } catch (Exception ex) {
                log.warn("reservation-service fallback call failed for reservationId={}: {}", reservationId, ex.getMessage());
                return Optional.empty();
            }
        }
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
                    return execution.execute(request, body);
                })
                .build();
    }
}
