package com.seatflow.seatmap.repository;

import com.seatflow.seatmap.model.entity.VenueSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VenueSectionRepository extends JpaRepository<VenueSection, UUID> {

    List<VenueSection> findByVenueIdOrderByNameAsc(UUID venueId);

    boolean existsByVenueIdAndName(UUID venueId, String name);
}
