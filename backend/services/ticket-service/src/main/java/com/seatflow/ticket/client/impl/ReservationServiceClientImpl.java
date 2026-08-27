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
        try {
            return circuitBreaker.executeSupplier(() -> fetchReservation(reservationId));
        } catch (CallNotPermittedException | RestClientException e) {
            log.warn("reservation-service call failed for reservationId={}: {}", reservationId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ReservationClientResponse> fetchReservation(UUID reservationId) {
        try {
            ReservationClientResponse response = client().get()
                    .uri("/api/reservations/{reservationId}", reservationId)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new InterServiceClientException(
                                "reservation-service returned error " + res.getStatusCode() + " for reservationId=" + reservationId);
                    })
                    .body(ReservationClientResponse.class);
            return Optional.ofNullable(response);
        } catch (InterServiceClientException e) {
            log.warn("reservation-service error for reservationId={}: {}", reservationId, e.getMessage());
            return Optional.empty();
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
