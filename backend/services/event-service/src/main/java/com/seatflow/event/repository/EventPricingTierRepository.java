package com.seatflow.event.repository;

import com.seatflow.event.model.entity.EventPricingTier;
import com.seatflow.event.repository.projection.EventPriceRangeSummaryProjection;
import com.seatflow.event.repository.projection.PriceRangeProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventPricingTierRepository extends JpaRepository<EventPricingTier, UUID> {

    List<EventPricingTier> findByEvent_IdOrderByPriceAsc(UUID eventId);

    boolean existsByEvent_Id(UUID eventId);

    Optional<EventPricingTier> findByEvent_IdAndSectionIdAndCategoryName(UUID eventId, UUID sectionId, String categoryName);

    @Query("SELECT MIN(p.price) AS minPrice, MAX(p.price) AS maxPrice, MIN(p.currency) AS currency FROM EventPricingTier p WHERE p.event.id = :eventId")
    PriceRangeProjection findPriceRangeByEventId(@Param("eventId") UUID eventId);

    @Query("SELECT p.event.id AS eventId, MIN(p.price) AS minPrice, MAX(p.price) AS maxPrice, MIN(p.currency) AS currency FROM EventPricingTier p WHERE p.event.id IN :eventIds GROUP BY p.event.id")
    List<EventPriceRangeSummaryProjection> findPriceRangesByEventIds(@Param("eventIds") Collection<UUID> eventIds);
}
