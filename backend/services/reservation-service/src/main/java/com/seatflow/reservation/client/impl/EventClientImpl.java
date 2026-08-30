package com.seatflow.reservation.client.impl;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.reservation.client.EventClient;
import com.seatflow.reservation.client.dto.EventPricingDetails;
import com.seatflow.reservation.client.dto.EventSeatMapClientResponse;
import com.seatflow.reservation.client.dto.SeatMapSectionClientDto;
import com.seatflow.reservation.client.dto.SeatMapSeatClientDto;
import com.seatflow.reservation.client.dto.SeatPricingDetails;
import com.seatflow.reservation.client.exception.EventClientUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class EventClientImpl implements EventClient {

    private static final String CIRCUIT_BREAKER_NAME = "eventService";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final String SEAT_MAP_PATH = "/api/events/{eventId}/seat-map";
    private static final String PUBLISHED_STATUS = "PUBLISHED";

    private final RestClient.Builder loadBalancedBuilder;
    private final CircuitBreaker circuitBreaker;
    private final String serviceId;
    private volatile RestClient restClient;

    public EventClientImpl(
            @Qualifier("eventServiceLoadBalancedBuilder") RestClient.Builder loadBalancedBuilder,
            CircuitBreakerRegistry circuitBreakerRegistry,
            @Value("${event-service.service-id:event-service}") String serviceId) {
        this.loadBalancedBuilder = loadBalancedBuilder;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        this.serviceId = serviceId;
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
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return loadBalancedBuilder
                .baseUrl("http://" + serviceId)
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    CorrelationContext.getCorrelationId()
                            .ifPresent(id -> request.getHeaders().set("X-Correlation-Id", id));
                    return execution.execute(request, body);
                })
                .build();
    }

    @Override
    public EventPricingDetails getEventSeatPricing(UUID eventId, Set<UUID> requestedSeatIds) {
        if (requestedSeatIds == null || requestedSeatIds.isEmpty()) {
            throw new ValidationException("At least one seat must be requested", ErrorCode.INVALID_REQUEST);
        }
        try {
            return circuitBreaker.executeSupplier(() -> doGetPricing(eventId, requestedSeatIds));
        } catch (CallNotPermittedException e) {
            throw new EventClientUnavailableException("Event service circuit is open for eventId=" + eventId, e);
        }
    }

    private EventPricingDetails doGetPricing(UUID eventId, Set<UUID> requestedSeatIds) {
        EventSeatMapClientResponse response = client().get()
                .uri(SEAT_MAP_PATH, eventId)
                .retrieve()
                .body(EventSeatMapClientResponse.class);

        if (response == null) {
            throw new EventClientUnavailableException("Event service returned empty seat-map for eventId=" + eventId);
        }

        return mapToPricingDetails(eventId, response, requestedSeatIds);
    }

    private EventPricingDetails mapToPricingDetails(UUID eventId,
                                                    EventSeatMapClientResponse response,
                                                    Set<UUID> requestedSeatIds) {
        if (!PUBLISHED_STATUS.equalsIgnoreCase(response.status())) {
            throw new ValidationException("Event is not published and cannot be reserved", ErrorCode.INVALID_REQUEST);
        }

        Instant eventDate = response.eventDate();
        if (eventDate == null || eventDate.isBefore(Instant.now().plus(Duration.ofMinutes(15)))) {
            throw new ValidationException("Event is in the past or too close to start time", ErrorCode.INVALID_REQUEST);
        }

        List<SeatMapSectionClientDto> sections = response.sections();
        if (sections == null || sections.isEmpty()) {
            throw new EventClientUnavailableException("Seat map unavailable for eventId=" + eventId);
        }

        Map<UUID, BigDecimal> seatPrices = new HashMap<>();
        Map<UUID, SeatPricingDetails> seatDetails = new HashMap<>();
        for (SeatMapSectionClientDto section : sections) {
            var pricingTiers = section.pricingTiers() == null
                    ? List.<com.seatflow.reservation.client.dto.PricingTierClientDto>of()
                    : section.pricingTiers().stream()
                            .filter(tier -> tier.id() != null && tier.price() != null && tier.price().signum() > 0)
                            .toList();
            BigDecimal sectionPrice = pricingTiers.isEmpty()
                    ? BigDecimal.ZERO
                    : pricingTiers.getFirst().price();
            if (section.seats() != null) {
                for (SeatMapSeatClientDto seat : section.seats()) {
                    // The event service returns the complete seat map. Only requested seats
                    // belong to this reservation; including the rest inflates the reservation
                    // total by the price of every seat in the venue.
                    if (seat.seatId() != null
                            && requestedSeatIds.contains(seat.seatId())
                            && Boolean.TRUE.equals(seat.isActive())) {
                        seatPrices.put(seat.seatId(), sectionPrice);
                        seatDetails.put(seat.seatId(), new SeatPricingDetails(
                                section.sectionId(),
                                section.name(),
                                seat.rowLabel(),
                                seat.seatNumber(),
                                pricingTiers));
                    }
                }
            }
        }

        List<UUID> missing = requestedSeatIds.stream()
                .filter(id -> !seatPrices.containsKey(id))
                .toList();
        if (!missing.isEmpty()) {
            throw new ValidationException("Requested seats not found in event seat map: " + missing, ErrorCode.INVALID_REQUEST);
        }

        return new EventPricingDetails(
                eventId,
                response.status(),
                eventDate,
                new ArrayList<>(requestedSeatIds),
                seatPrices,
                seatDetails);
    }
}
