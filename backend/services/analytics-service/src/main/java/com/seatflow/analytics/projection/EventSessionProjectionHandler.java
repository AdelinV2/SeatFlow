package com.seatflow.analytics.projection;

import com.seatflow.analytics.model.entity.AnalyticsSessionFact;
import com.seatflow.analytics.repository.AnalyticsSessionFactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.seatflow.analytics.projection.ReservationProjectionHandler.latest;

/**
 * Session metadata reducer (TASK-P14-003).
 *
 * <p>Current verified {@code EVENT_*} payloads carry parent-event display metadata but no trusted
 * per-session snapshot (no session lifecycle producer exists yet), and no payload carries an
 * authoritative capacity — so display fields stay null and {@code capacity_snapshot} stays null
 * (later APIs/UI must expose occupancy as unavailable, never fabricated).
 *
 * <p>This reducer therefore guarantees correlation rows exist with an ordering guard
 * ({@code last_source_event_at} is a max, so older deliveries never overwrite newer state) and
 * records {@code EVENT_*} lifecycle receipt without mutating session snapshots. When a real
 * session-lifecycle contract lands, its display/capacity fields plug into
 * {@link #updateSnapshot} under the same guard.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventSessionProjectionHandler {

    private final AnalyticsSessionFactRepository sessionFacts;

    /**
     * Ensure the correlation row for one session exists. Null IDs are ignored (unknown
     * correlation is temporary read-model state, never a failure).
     *
     * @return the session identity when a row is now present.
     */
    @Transactional
    public Optional<UUID> ensureSession(UUID eventId, UUID eventSessionId, Instant sourceEventAt) {
        if (eventId == null || eventSessionId == null || sourceEventAt == null) {
            return Optional.empty();
        }
        AnalyticsSessionFact fact = sessionFacts.findById(eventSessionId).orElse(null);
        if (fact == null) {
            sessionFacts.save(AnalyticsSessionFact.builder()
                    .eventSessionId(eventSessionId)
                    .eventId(eventId)
                    .lastSourceEventAt(sourceEventAt)
                    .updatedAt(Instant.now())
                    .build());
        } else {
            fact.setLastSourceEventAt(latest(fact.getLastSourceEventAt(), sourceEventAt));
            fact.setUpdatedAt(Instant.now());
            sessionFacts.save(fact);
        }
        return Optional.of(eventSessionId);
    }

    /**
     * Apply a trusted session snapshot when a future session-lifecycle contract provides one.
     * Null display fields never erase known values; capacity must be an authoritative snapshot
     * (never derived from sold/issued counts) or stay null.
     */
    @Transactional
    public void updateSnapshot(
            UUID eventId, UUID eventSessionId, Instant sourceEventAt,
            String eventTitle, String sessionLabel, Instant startsAt, Instant endsAt,
            String status, Integer capacitySnapshot) {
        if (eventId == null || eventSessionId == null || sourceEventAt == null) {
            return;
        }
        AnalyticsSessionFact fact = sessionFacts.findById(eventSessionId).orElse(null);
        if (fact == null) {
            fact = AnalyticsSessionFact.builder()
                    .eventSessionId(eventSessionId)
                    .eventId(eventId)
                    .lastSourceEventAt(sourceEventAt)
                    .updatedAt(Instant.now())
                    .build();
        } else if (sourceEventAt.isBefore(fact.getLastSourceEventAt())) {
            log.debug("Stale session snapshot ignored. eventSessionId={}", eventSessionId);
            return;
        }
        if (eventTitle != null) {
            fact.setEventTitle(eventTitle);
        }
        if (sessionLabel != null) {
            fact.setSessionLabel(sessionLabel);
        }
        if (startsAt != null) {
            fact.setStartsAt(startsAt);
        }
        if (endsAt != null) {
            fact.setEndsAt(endsAt);
        }
        if (status != null) {
            fact.setStatus(status);
        }
        if (capacitySnapshot != null) {
            if (capacitySnapshot < 0) {
                log.warn("Negative capacity snapshot rejected. eventSessionId={}", eventSessionId);
            } else {
                fact.setCapacitySnapshot(capacitySnapshot);
            }
        }
        fact.setLastSourceEventAt(latest(fact.getLastSourceEventAt(), sourceEventAt));
        fact.setUpdatedAt(Instant.now());
        sessionFacts.save(fact);
    }

    /** Parent event lifecycle receipt: no session mutation with current payloads (see class doc). */
    @Transactional
    public ProjectionImpact onEventLifecycle(String eventType, String envelopeEventId, Instant occurredAt) {
        log.debug("Event lifecycle received. eventType={} envelopeEventId={}", eventType, envelopeEventId);
        return ProjectionImpact.empty();
    }

    /** Event identity for a known session, if correlated yet. */
    @Transactional(readOnly = true)
    public Optional<UUID> sessionEventId(UUID eventSessionId) {
        if (eventSessionId == null) {
            return Optional.empty();
        }
        return sessionFacts.findById(eventSessionId).map(AnalyticsSessionFact::getEventId);
    }
}
