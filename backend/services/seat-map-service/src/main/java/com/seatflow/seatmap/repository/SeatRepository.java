package com.seatflow.seatmap.repository;

import com.seatflow.seatmap.model.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findBySectionIdOrderByGridYAscGridXAsc(UUID sectionId);

    @Query("SELECT s FROM Seat s WHERE s.section.id = :sectionId AND s.isActive = true ORDER BY s.gridY ASC, s.gridX ASC")
    List<Seat> findActiveSeatsBySectionId(@Param("sectionId") UUID sectionId);

    @Query("SELECT s FROM Seat s WHERE s.section.venue.id = :venueId AND s.isActive = true ORDER BY s.section.name ASC, s.gridY ASC, s.gridX ASC")
    List<Seat> findActiveSeatsForVenueLayout(@Param("venueId") UUID venueId);

    Optional<Seat> findByIdAndSectionId(UUID seatId, UUID sectionId);

    long countBySectionIdAndIsActiveTrue(UUID sectionId);

    @Query("SELECT COUNT(s) FROM Seat s WHERE s.section.venue.id = :venueId AND s.isActive = true")
    long countActiveSeatsByVenueId(@Param("venueId") UUID venueId);
}
