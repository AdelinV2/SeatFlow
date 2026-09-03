package com.seatflow.seatmap.repository;

import com.seatflow.seatmap.model.entity.VenueSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VenueSectionRepository extends JpaRepository<VenueSection, UUID> {

    List<VenueSection> findByVenueIdOrderByNameAsc(UUID venueId);

    // Explicit JPQL: derived OrderBy would render the zIndex path as "ZIndex",
    // which JPQL attribute resolution rejects. Signatures match TASK-P11-002 §5.2.
    @Query("""
            SELECT s FROM VenueSection s
            WHERE s.venue.id = :venueId AND s.isActive = true
            ORDER BY s.zIndex ASC, s.name ASC
            """)
    List<VenueSection> findByVenueIdAndIsActiveTrueOrderByZIndexAscNameAsc(@Param("venueId") UUID venueId);

    @Query("""
            SELECT s FROM VenueSection s
            WHERE s.venue.id = :venueId
            ORDER BY s.zIndex ASC, s.name ASC
            """)
    List<VenueSection> findByVenueIdOrderByZIndexAscNameAsc(@Param("venueId") UUID venueId);

    boolean existsByVenueIdAndName(UUID venueId, String name);
}
