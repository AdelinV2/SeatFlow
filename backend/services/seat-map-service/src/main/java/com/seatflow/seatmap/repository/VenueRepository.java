package com.seatflow.seatmap.repository;

import com.seatflow.seatmap.model.entity.Venue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface VenueRepository extends JpaRepository<Venue, UUID> {

    Page<Venue> findAll(Pageable pageable);

    @Query("SELECT v FROM Venue v WHERE (:city IS NULL OR v.city = :city) AND (:name IS NULL OR LOWER(v.name) LIKE LOWER(CONCAT('%', :name, '%')))")
    Page<Venue> findByFilters(@Param("city") String city, @Param("name") String name, Pageable pageable);

    boolean existsByNameAndCity(String name, String city);
}
