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
     * Session-aware completion candidates (P12-007 / ADR-011).
     *
     * <p>An event may complete only when it owns at least one session and no
     * non-cancelled session still ends in the future. Cancelled sessions are
     * ignored entirely (a future CANCELLED session never blocks completion).
     * The pre-Phase-12 {@code eventDate} fallback was removed: events without
     * any session never complete here (an operator must cancel them or add
     * sessions explicitly). Never completes an event while a later
     * non-cancelled session remains.
     */
    @Query(value = """
            SELECT e FROM Event e
            WHERE e.status = com.seatflow.event.model.enums.EventStatus.PUBLISHED
            AND EXISTS (
                SELECT s FROM com.seatflow.event.model.entity.EventSession s
                WHERE s.event = e)
            AND NOT EXISTS (
                SELECT s FROM com.seatflow.event.model.entity.EventSession s
                WHERE s.event = e
                AND s.status <> com.seatflow.event.model.enums.EventSessionStatus.CANCELLED
                AND s.endsAt > :now)
            ORDER BY e.createdAt ASC""")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    List<Event> findPublishedCompletableForUpdate(@Param("now") Instant now, Pageable pageable);
}
