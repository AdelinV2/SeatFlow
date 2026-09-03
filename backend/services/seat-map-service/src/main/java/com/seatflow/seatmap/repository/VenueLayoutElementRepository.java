package com.seatflow.seatmap.repository;

import com.seatflow.seatmap.model.entity.VenueLayoutElement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface VenueLayoutElementRepository extends JpaRepository<VenueLayoutElement, UUID> {

    // Explicit JPQL: derived OrderBy would render the zIndex path as "ZIndex",
    // which JPQL attribute resolution rejects. Signature matches TASK-P11-002 §5.2.
    @Query("""
            SELECT e FROM VenueLayoutElement e
            WHERE e.venue.id = :venueId
            ORDER BY e.zIndex ASC, e.id ASC
            """)
    List<VenueLayoutElement> findByVenueIdOrderByZIndexAscIdAsc(@Param("venueId") UUID venueId);

    void deleteByVenueIdAndIdIn(UUID venueId, Collection<UUID> ids);
}
