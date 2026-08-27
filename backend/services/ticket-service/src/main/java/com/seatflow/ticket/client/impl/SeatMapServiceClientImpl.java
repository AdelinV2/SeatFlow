package com.seatflow.ticket.client.impl;

import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.ticket.client.SeatMapServiceClient;
import com.seatflow.ticket.client.dto.VenueClientResponse;
import com.seatflow.ticket.client.dto.VenueSeatMapLayoutClientResponse;
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
public class SeatMapServiceClientImpl implements SeatMapServiceClient {

    private static final String CIRCUIT_BREAKER_NAME = "seatMapService";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient.Builder loadBalancedBuilder;
    private final CircuitBreaker circuitBreaker;
    private final String baseUrl;
    private volatile RestClient restClient;

    public SeatMapServiceClientImpl(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedBuilder,
            CircuitBreakerRegistry circuitBreakerRegistry,
            @Value("${seat-map-service.base-url:http://seat-map-service}") String baseUrl) {
        this.loadBalancedBuilder = loadBalancedBuilder;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        this.baseUrl = baseUrl;
    }

    @Override
    public Optional<VenueClientResponse> getVenueById(UUID venueId) {
        try {
            return circuitBreaker.executeSupplier(() -> fetchVenue(venueId));
        } catch (CallNotPermittedException | RestClientException e) {
            log.warn("seat-map-service call failed for venueId={}: {}", venueId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<VenueSeatMapLayoutClientResponse> getVenueLayout(UUID venueId) {
        try {
            return circuitBreaker.executeSupplier(() -> fetchVenueLayout(venueId));
        } catch (CallNotPermittedException | RestClientException e) {
            log.warn("seat-map-service layout call failed for venueId={}: {}", venueId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<VenueClientResponse> fetchVenue(UUID venueId) {
        try {
            VenueClientResponse response = client().get()
                    .uri("/api/venues/{venueId}", venueId)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new InterServiceClientException(
                                "seat-map-service returned error " + res.getStatusCode() + " for venueId=" + venueId);
                    })
                    .body(VenueClientResponse.class);
            return Optional.ofNullable(response);
        } catch (InterServiceClientException e) {
            log.warn("seat-map-service error for venueId={}: {}", venueId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<VenueSeatMapLayoutClientResponse> fetchVenueLayout(UUID venueId) {
        try {
            VenueSeatMapLayoutClientResponse response = client().get()
                    .uri("/api/venues/{venueId}/layout", venueId)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new InterServiceClientException(
                                "seat-map-service returned error " + res.getStatusCode() + " for layout venueId=" + venueId);
                    })
                    .body(VenueSeatMapLayoutClientResponse.class);
            return Optional.ofNullable(response);
        } catch (InterServiceClientException e) {
            log.warn("seat-map-service layout error for venueId={}: {}", venueId, e.getMessage());
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
