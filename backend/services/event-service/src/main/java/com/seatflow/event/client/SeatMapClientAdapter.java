package com.seatflow.event.client;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

@Component
public class SeatMapClientAdapter implements SeatMapClient {

    private static final Logger log = LoggerFactory.getLogger(SeatMapClientAdapter.class);

    private final RestClient restClient;

    public SeatMapClientAdapter(RestClient.Builder restClientBuilder,
                                @Value("${seatmap.service.url:http://localhost:8082}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public boolean venueExists(UUID venueId) {
        try {
            restClient.get()
                    .uri("/api/venues/{venueId}", venueId)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public boolean sectionBelongsToVenue(UUID venueId, UUID sectionId) {
        try {
            VenueLayout layout = restClient.get()
                    .uri("/api/venues/{venueId}/layout", venueId)
                    .retrieve()
                    .body(VenueLayout.class);
            if (layout == null || layout.sections() == null) {
                return false;
            }
            return layout.sections().stream()
                    .anyMatch(section -> sectionId.equals(section.sectionId()));
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public VenueSeatMapResponse getVenueSeatMap(UUID venueId) {
        VenueLayout layout = restClient.get()
                .uri("/api/venues/{venueId}/layout", venueId)
                .retrieve()
                .body(VenueLayout.class);
        if (layout == null) {
            throw new IllegalStateException("Seat-map service returned no layout for venue " + venueId);
        }
        return new VenueSeatMapResponse(
                layout.venueId(),
                layout.name(),
                layout.capacity(),
                layout.sections());
    }

    private record VenueLayout(
            UUID venueId,
            String name,
            Integer capacity,
            List<VenueSectionResponse> sections) {
    }
}
