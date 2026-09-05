package com.seatflow.seatmap.service;

import com.seatflow.seatmap.model.entity.Venue;
import com.seatflow.seatmap.web.dto.request.SaveVenueLayoutRequest;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic structural validation for typed venue layout snapshots.
 * Runs before any entity mutation (TASK-P11-003 §5.3).
 */
public interface LayoutValidationService {

    /**
     * Immutable snapshot of existing layout identities owned by the target venue.
     * Service-local value object, never exposed as a web DTO.
     */
    record ExistingLayoutIds(
        Set<UUID> sectionIds,
        Map<UUID, UUID> seatIdToSectionId,
        Set<UUID> elementIds
    ) {
        public ExistingLayoutIds {
            sectionIds = sectionIds == null ? Set.of() : Set.copyOf(sectionIds);
            seatIdToSectionId = seatIdToSectionId == null ? Map.of() : Map.copyOf(seatIdToSectionId);
            elementIds = elementIds == null ? Set.of() : Set.copyOf(elementIds);
        }

        public static ExistingLayoutIds empty() {
            return new ExistingLayoutIds(Set.of(), Map.of(), Set.of());
        }
    }

    /**
     * Validate the request against rules 1-14.
     * Returns normally when valid; throws ValidationException with INVALID_REQUEST otherwise.
     */
    void validate(Venue venue, SaveVenueLayoutRequest request, ExistingLayoutIds ids);
}
