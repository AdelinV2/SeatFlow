package com.seatflow.event.repository;

import com.seatflow.event.model.entity.EventSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence queries for {@link EventSession}.
 *
 * <p>Listing by event always orders by {@code startsAt} then {@code id} so
 * repeated reads are deterministic even when two sessions share a start
 * instant. No method here may infer an arbitrary session when an event owns
 * more than one session. Callers must select explicitly.
 */
@Repository
public interface EventSessionRepository extends JpaRepository<EventSession, UUID> {

    List<EventSession> findByEvent_IdOrderByStartsAtAscIdAsc(UUID eventId);

    Optional<EventSession> findByIdAndEvent_Id(UUID id, UUID eventId);

    List<EventSession> findByLegacyBackfillTrueOrderByStartsAtAscIdAsc();

    long countByLegacyBackfillTrue();

    long countByEvent_IdAndLegacyBackfillTrue(UUID eventId);

    boolean existsByEvent_IdAndLegacyBackfillTrue(UUID eventId);
}
