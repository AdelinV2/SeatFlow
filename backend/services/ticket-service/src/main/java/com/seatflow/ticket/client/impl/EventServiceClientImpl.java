package com.seatflow.ticket.client.impl;

import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.ticket.client.EventServiceClient;
import com.seatflow.ticket.client.dto.EventClientResponse;
import com.seatflow.ticket.client.dto.EventSeatMapClientResponse;
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
public class EventServiceClientImpl implements EventServiceClient {

    private static final String CIRCUIT_BREAKER_NAME = "eventService";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient.Builder loadBalancedBuilder;
    private final CircuitBreaker circuitBreaker;
    private final String baseUrl;
    private volatile RestClient restClient;

    public EventServiceClientImpl(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedBuilder,
            CircuitBreakerRegistry circuitBreakerRegistry,
            @Value("${event-service.base-url:http://event-service}") String baseUrl) {
        this.loadBalancedBuilder = loadBalancedBuilder;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        this.baseUrl = baseUrl;
    }

    @Override
    public Optional<EventClientResponse> getEventById(UUID eventId) {
        try {
            return circuitBreaker.executeSupplier(() -> fetchEvent(eventId));
        } catch (CallNotPermittedException | RestClientException e) {
            log.warn("event-service call failed for eventId={}: {}", eventId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<EventSeatMapClientResponse> getEventSeatMap(UUID eventId) {
        try {
            return circuitBreaker.executeSupplier(() -> fetchEventSeatMap(eventId));
        } catch (CallNotPermittedException | RestClientException e) {
            log.warn("event-service seat-map call failed for eventId={}: {}", eventId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<EventClientResponse> fetchEvent(UUID eventId) {
        try {
            EventClientResponse response = client().get()
                    .uri("/api/events/{eventId}", eventId)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new InterServiceClientException(
                                "event-service returned error " + res.getStatusCode() + " for eventId=" + eventId);
                    })
                    .body(EventClientResponse.class);
            return Optional.ofNullable(response);
        } catch (InterServiceClientException e) {
            log.warn("event-service error for eventId={}: {}", eventId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<EventSeatMapClientResponse> fetchEventSeatMap(UUID eventId) {
        try {
            EventSeatMapClientResponse response = client().get()
                    .uri("/api/events/{eventId}/seat-map", eventId)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new InterServiceClientException(
                                "event-service returned error " + res.getStatusCode() + " for seat-map eventId=" + eventId);
                    })
                    .body(EventSeatMapClientResponse.class);
            return Optional.ofNullable(response);
        } catch (InterServiceClientException e) {
            log.warn("event-service seat-map error for eventId={}: {}", eventId, e.getMessage());
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
