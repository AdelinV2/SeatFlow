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
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
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
            @Qualifier("reservationServiceLoadBalancedBuilder") RestClient.Builder loadBalancedBuilder,
            CircuitBreakerRegistry circuitBreakerRegistry,
            @Value("${reservation-service.base-url:http://reservation-service}") String baseUrl) {
        this.loadBalancedBuilder = loadBalancedBuilder;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        this.baseUrl = baseUrl;
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

    @Override
    public ReservationClientResponse getReservation(UUID reservationId) {
        try {
            return circuitBreaker.executeSupplier(() -> fetchReservation(reservationId));
        } catch (CallNotPermittedException e) {
            log.error("Circuit breaker open for reservation-service call on reservationId={}", reservationId, e);
            throw new ReservationClientUnavailableException("Reservation service is temporarily unavailable", e);
        }
    }

    private ReservationClientResponse fetchReservation(UUID reservationId) {
        log.debug("Fetching reservation details from reservation-service for reservationId={}", reservationId);

        ReservationClientResponse response = client().get()
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
            throw new ReservationClientUnavailableException(
                    "Empty response from reservation-service for reservationId=" + reservationId);
        }

        return response;
    }
}
