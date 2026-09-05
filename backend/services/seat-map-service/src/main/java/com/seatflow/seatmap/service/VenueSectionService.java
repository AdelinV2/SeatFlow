package com.seatflow.seatmap.service;

import com.seatflow.seatmap.web.dto.request.CreateVenueSectionRequest;
import com.seatflow.seatmap.web.dto.request.UpdateSeatStatusRequest;
import com.seatflow.seatmap.web.dto.response.SeatResponse;
import com.seatflow.seatmap.web.dto.response.VenueSectionResponse;

import java.util.UUID;

public interface VenueSectionService {

    /**
     * Create a new section in a venue and auto-generate the seat grid (rowCount × colCount).
     * Row labels are generated alphabetically: A, B, ..., Z, AA, AB, ...
     * Grid coordinates: grid_x = column index (0-based), grid_y = row index (0-based).
     * Writes a VenueSectionCreatedEvent to outbox_events in the same transaction.
     * Rejects duplicate section names within the same venue with ConflictException.
     */
    VenueSectionResponse createSection(UUID venueId, CreateVenueSectionRequest request);

    /**
     * Toggle a seat's active/inactive status within a section.
     * Throws ResourceNotFoundException if seat or section does not exist.
     */
    SeatResponse updateSeatStatus(UUID venueId, UUID sectionId, UUID seatId, UpdateSeatStatusRequest request);

    /**
     * Delete a section and all its associated seats from a venue.
     * Legacy alias for {@link #deactivateSection(UUID, UUID)}: rows are soft-deactivated
     * ({@code is_active = false}) and never hard-deleted, and the venue layout version
     * is incremented. Kept for backward compatibility with existing callers.
     * Throws ResourceNotFoundException if venue or section does not exist.
     */
    void deleteSection(UUID venueId, UUID sectionId);

    /**
     * Soft-deactivate a section and all its seats, then advance the venue layout version.
     * Uses the venue pessimistic-write lock so concurrent editor saves observe the bump.
     * Throws ResourceNotFoundException if venue or section does not exist.
     */
    void deactivateSection(UUID venueId, UUID sectionId);
}
