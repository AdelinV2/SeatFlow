package com.seatflow.seatmap.service;

import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.seatmap.web.dto.request.CreateVenueRequest;
import com.seatflow.seatmap.web.dto.request.UpdateVenueRequest;
import com.seatflow.seatmap.web.dto.response.VenueDetailResponse;
import com.seatflow.seatmap.web.dto.response.VenueResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface VenueService {

    /**
     * Create a new venue. Writes a VenueCreatedEvent to outbox_events in the same transaction.
     * Rejects duplicate (name, city) combinations with ConflictException.
     */
    VenueResponse createVenue(CreateVenueRequest request);

    /**
     * Update an existing venue's mutable fields (name, address, city, country, capacity).
     * Only updates non-null fields from the request.
     */
    VenueResponse updateVenue(UUID venueId, UpdateVenueRequest request);

    /**
     * Retrieve a single venue by ID with its sections.
     * Throws ResourceNotFoundException if venue does not exist.
     */
    VenueDetailResponse getVenueById(UUID venueId);

    /**
     * List all venues with pagination and optional filtering by city and name search.
     */
    PagedResult<VenueResponse> listVenues(String city, String name, Pageable pageable);
}
