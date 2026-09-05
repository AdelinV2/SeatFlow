package com.seatflow.seatmap.service;

import com.seatflow.seatmap.web.dto.request.SaveVenueLayoutRequest;
import com.seatflow.seatmap.web.dto.response.VenueSeatMapLayoutResponse;

import java.util.UUID;

/**
 * Atomic versioned layout read, validate and save operations for the advanced editor.
 * TASK-P11-004: single-transaction full-snapshot save guarded by {@code layout_version}.
 */
public interface VenueLayoutService {

    /**
     * Load the complete editable layout including inactive rows and all elements.
     * Throws ResourceNotFoundException if the venue does not exist.
     */
    VenueSeatMapLayoutResponse getEditableLayout(UUID venueId);

    /**
     * Validate a layout snapshot without writing. Runs TASK-P11-003 rules only.
     * Throws ResourceNotFoundException if the venue does not exist.
     * Throws ValidationException with INVALID_REQUEST when invalid.
     */
    void validateLayout(UUID venueId, SaveVenueLayoutRequest request);

    /**
     * Atomically persist a complete layout snapshot.
     * Locks the venue row, compares layoutVersion, validates, applies all
     * section/seat/element changes, checks capacity, increments the version once.
     * Throws ConflictException with CONFLICT (SF_409_CONFLICT) on stale version.
     */
    VenueSeatMapLayoutResponse saveLayout(UUID venueId, SaveVenueLayoutRequest request);
}
