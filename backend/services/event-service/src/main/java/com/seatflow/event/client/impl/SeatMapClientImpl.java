package com.seatflow.event.client.impl;

import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.event.client.SeatMapClient;
import com.seatflow.event.client.SeatMapClientUnavailableException;
import com.seatflow.event.client.SeatMapVenueLayout;
import com.seatflow.event.client.SeatMapVenueSection;
import com.seatflow.event.client.SeatMapVenueSeat;
import com.seatflow.event.client.VenueValidationPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class SeatMapClientImpl implements SeatMapClient {

    private static final Logger log = LoggerFactory.getLogger(SeatMapClientImpl.class);
    private static final String CIRCUIT_BREAKER_NAME = "seatMapClient";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);
    private static final long GRID_UNIT = 44L;

    private final RestClient.Builder loadBalancedBuilder;
    private final CircuitBreaker circuitBreaker;
    private final String serviceId;
    private volatile RestClient restClient;

    public SeatMapClientImpl(
            @Qualifier("seatMapLoadBalancedBuilder") RestClient.Builder loadBalancedBuilder,
            CircuitBreakerRegistry circuitBreakerRegistry,
            @Value("${seat-map-service.service-id:seat-map-service}") String serviceId) {
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
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
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
    public boolean venueExists(UUID venueId) {
        return circuitBreaker.executeSupplier(() -> {
            try {
                client().get()
                        .uri("/api/venues/{venueId}", venueId)
                        .retrieve()
                        .toBodilessEntity();
                return true;
            } catch (HttpStatusCodeException e) {
                if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                    return false;
                }
                throw new SeatMapClientUnavailableException(
                        "Seat-map service returned status " + e.getStatusCode() + " for venue " + venueId, e);
            } catch (Exception e) {
                throw new SeatMapClientUnavailableException("Seat-map service unavailable for venue " + venueId, e);
            }
        });
    }

    @Override
    public boolean sectionBelongsToVenue(UUID venueId, UUID sectionId) {
        try {
            SeatMapVenueLayout layout = getVenueLayout(venueId);
            return layout.sections() != null
                    && layout.sections().stream().anyMatch(s -> sectionId.equals(s.sectionId()));
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }

    @Override
    public SeatMapVenueLayout getVenueLayout(UUID venueId) {
        return circuitBreaker.executeSupplier(() -> fetchLayout(venueId));
    }

    private SeatMapVenueLayout fetchLayout(UUID venueId) {
        try {
            VenueLayout layout = client().get()
                    .uri("/api/venues/{venueId}/layout", venueId)
                    .retrieve()
                    .body(VenueLayout.class);
            if (layout == null || layout.venueId() == null) {
                throw new ResourceNotFoundException("Venue", venueId);
            }
            List<SeatMapVenueSection> sections = layout.sections() == null ? List.of()
                    : layout.sections().stream().map(this::toSection).toList();
            List<SeatMapVenueLayout.LayoutElement> elements = layout.elements() == null ? List.of()
                    : layout.elements().stream().map(this::toElement).toList();
            long total = layout.totalConfiguredSeats() != null ? layout.totalConfiguredSeats() : 0L;
            long version = layout.layoutVersion() != null ? layout.layoutVersion() : 0L;
            log.info("Fetched venue layout. venueId={}, layoutVersion={}, sections={}, elements={}, totalConfiguredSeats={}",
                    venueId, version, sections.size(), elements.size(), total);
            return new SeatMapVenueLayout(layout.venueId(), layout.name(), layout.capacity(), total, sections,
                    version, elements);
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new ResourceNotFoundException("Venue", venueId);
            }
            throw new SeatMapClientUnavailableException(
                    "Seat-map service returned status " + e.getStatusCode() + " for venue " + venueId, e);
        } catch (Exception e) {
            throw new SeatMapClientUnavailableException("Seat-map service unavailable for venue " + venueId, e);
        }
    }

    private SeatMapVenueSection toSection(VenueSection section) {
        List<SeatMapVenueSeat> seats = section.seats() == null ? List.of()
                : section.seats().stream().map(this::toSeat).toList();
        return new SeatMapVenueSection(
                section.sectionId(),
                section.name(),
                section.rowCount(),
                section.colCount(),
                section.isActive() != null ? section.isActive() : Boolean.TRUE,
                section.positionX() != null ? section.positionX() : BigDecimal.ZERO,
                section.positionY() != null ? section.positionY() : BigDecimal.ZERO,
                section.width() != null ? section.width() : gridExtent(section.colCount()),
                section.height() != null ? section.height() : gridExtent(section.rowCount()),
                section.rotationDeg() != null ? section.rotationDeg() : BigDecimal.ZERO,
                section.zIndex() != null ? section.zIndex() : 0,
                section.shapeMetadata(),
                seats);
    }

    private SeatMapVenueSeat toSeat(VenueSeat seat) {
        return new SeatMapVenueSeat(
                seat.seatId(),
                seat.rowLabel(),
                seat.seatNumber(),
                seat.gridX(),
                seat.gridY(),
                seat.isActive(),
                seat.positionX() != null ? seat.positionX() : gridToPosition(seat.gridX()),
                seat.positionY() != null ? seat.positionY() : gridToPosition(seat.gridY()));
    }

    private SeatMapVenueLayout.LayoutElement toElement(VenueElement element) {
        SeatMapVenueLayout.Geometry geometry = element.geometry() == null ? null
                : new SeatMapVenueLayout.Geometry(
                        element.geometry().x(),
                        element.geometry().y(),
                        element.geometry().width(),
                        element.geometry().height(),
                        element.geometry().rotationDeg());
        return new SeatMapVenueLayout.LayoutElement(
                element.elementId(), element.type(), element.label(), geometry, element.zIndex());
    }

    private static BigDecimal gridToPosition(Integer grid) {
        return grid == null ? BigDecimal.ZERO : BigDecimal.valueOf(grid * GRID_UNIT);
    }

    private static BigDecimal gridExtent(Integer cells) {
        return cells == null ? BigDecimal.ZERO : BigDecimal.valueOf(cells * GRID_UNIT);
    }

    private record VenueLayout(
            UUID venueId,
            String name,
            Integer capacity,
            Long totalConfiguredSeats,
            List<VenueSection> sections,
            Long layoutVersion,
            List<VenueElement> elements
    ) {
    }

    private record VenueSection(
            UUID sectionId,
            String name,
            Integer rowCount,
            Integer colCount,
            Boolean isActive,
            BigDecimal positionX,
            BigDecimal positionY,
            BigDecimal width,
            BigDecimal height,
            BigDecimal rotationDeg,
            Integer zIndex,
            Object shapeMetadata,
            List<VenueSeat> seats
    ) {
    }

    private record VenueSeat(
            UUID seatId,
            String rowLabel,
            Integer seatNumber,
            Integer gridX,
            Integer gridY,
            Boolean isActive,
            BigDecimal positionX,
            BigDecimal positionY
    ) {
    }

    private record VenueElement(
            UUID elementId,
            String type,
            String label,
            VenueGeometry geometry,
            Integer zIndex
    ) {
    }

    private record VenueGeometry(
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height,
            BigDecimal rotationDeg
    ) {
    }
}
