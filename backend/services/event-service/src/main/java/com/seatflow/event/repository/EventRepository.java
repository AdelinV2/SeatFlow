package com.seatflow.event.repository;

import com.seatflow.event.model.entity.Event;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventRepository extends JpaRepository<Event, UUID>, JpaSpecificationExecutor<Event> {

    @EntityGraph(attributePaths = "pricingTiers")
    Optional<Event> findWithPricingTiersById(UUID id);

    /**
     * Session-aware completion candidates.
     *
     * <p>An event may complete only when no non-cancelled session still ends in the
     * future. Events without any non-cancelled session fall back to the legacy
     * {@code eventDate} boundary so pre-Phase-12 behavior is preserved until every
     * event owns sessions. Never completes an event while a later session remains.
     */
    @Query(value = """
            SELECT e FROM Event e
            WHERE e.status = com.seatflow.event.model.enums.EventStatus.PUBLISHED
            AND NOT EXISTS (
                SELECT s FROM com.seatflow.event.model.entity.EventSession s
                WHERE s.event = e
                AND s.status <> com.seatflow.event.model.enums.EventSessionStatus.CANCELLED
                AND s.endsAt > :now)
            AND (
                EXISTS (
                    SELECT s FROM com.seatflow.event.model.entity.EventSession s
                    WHERE s.event = e
                    AND s.status <> com.seatflow.event.model.enums.EventSessionStatus.CANCELLED)
                OR e.eventDate <= :now)
            ORDER BY e.eventDate ASC""")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    List<Event> findPublishedCompletableForUpdate(@Param("now") Instant now, Pageable pageable);
}
