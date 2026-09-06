package com.seatflow.reservation.migration;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.reservation.client.EventClient;
import com.seatflow.reservation.client.dto.SessionBookingContextDto;
import com.seatflow.reservation.repository.ReservationRepository;
import com.seatflow.reservation.repository.SeatHoldRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-shot, idempotent backfill of {@code event_session_id} for rows created before
 * P12-003 (TASK-P12-003, ADR-011).
 *
 * <p>Each distinct legacy {@code event_id} is resolved through event-service
 * booking-context by validating the operator-supplied {@code eventId -> sessionId}
 * mapping: the trusted context must echo the same parent event id, otherwise the
 * run fails closed without writing anything for that event. The run never guesses
 * a session (in particular it never picks "the first" of several sessions).
 *
 * <p>Safe to rerun: only rows with {@code event_session_id IS NULL} are updated,
 * and the final gate verifies zero NULLs remain across both tables.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionInventoryBackfillService {

    private final ReservationRepository reservationRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final EventClient eventClient;

    public record BackfillResult(
            Map<UUID, UUID> appliedMappings,
            int reservationsUpdated,
            int seatHoldsUpdated) {
    }

    @Transactional
    public BackfillResult backfill(Map<UUID, UUID> legacyEventToSession) {
        if (legacyEventToSession == null || legacyEventToSession.isEmpty()) {
            throw new ValidationException("A non-empty legacy event -> session mapping is required",
                    ErrorCode.INVALID_REQUEST);
        }

        Map<UUID, UUID> applied = new LinkedHashMap<>();
        int reservationsUpdated = 0;
        int seatHoldsUpdated = 0;

        // Validate every mapping against event-service BEFORE mutating anything,
        // so a single bad entry fails the whole run closed.
        Map<UUID, SessionBookingContextDto> contexts = new LinkedHashMap<>();
        for (Map.Entry<UUID, UUID> entry : legacyEventToSession.entrySet()) {
            UUID eventId = entry.getKey();
            UUID sessionId = entry.getValue();
            if (eventId == null || sessionId == null) {
                throw new ValidationException("Legacy mapping must not contain null event or session ids",
                        ErrorCode.INVALID_REQUEST);
            }
            SessionBookingContextDto context = eventClient.getSessionBookingContext(sessionId);
            if (context == null || !eventId.equals(context.eventId())
                    || !sessionId.equals(context.eventSessionId())) {
                log.error("Fail-closed backfill abort: session does not belong to legacy event. eventId={}, sessionId={}",
                        eventId, sessionId);
                throw new ValidationException(
                        "Session " + sessionId + " does not resolve to legacy event " + eventId
                                + "; refusing to guess the backfill session",
                        ErrorCode.INVALID_REQUEST);
            }
            contexts.put(eventId, context);
        }

        for (Map.Entry<UUID, SessionBookingContextDto> entry : contexts.entrySet()) {
            UUID eventId = entry.getKey();
            UUID sessionId = entry.getValue().eventSessionId();
            int resCount = reservationRepository.backfillSessionIdForLegacyEvent(eventId, sessionId);
            int holdCount = seatHoldRepository.backfillSessionIdForLegacyEvent(eventId, sessionId);
            applied.put(eventId, sessionId);
            reservationsUpdated += resCount;
            seatHoldsUpdated += holdCount;
            log.info("Backfilled session inventory key. eventId={}, eventSessionId={}, reservations={}, seatHolds={}",
                    eventId, sessionId, resCount, holdCount);
        }

        verifyZeroNullSessions();

        return new BackfillResult(Map.copyOf(applied), reservationsUpdated, seatHoldsUpdated);
    }

    /**
     * Verification gate: zero rows may remain with {@code event_session_id IS NULL}.
     * V9 enforces the NOT NULL constraints; this gate remains the pre-migration
     * proof that no orphan survives the backfill.
     */
    @Transactional(readOnly = true)
    public void verifyZeroNullSessions() {
        long nullReservations = reservationRepository.countReservationsWithNullSession();
        long nullHolds = seatHoldRepository.countSeatHoldsWithNullSession();
        if (nullReservations > 0 || nullHolds > 0) {
            List<UUID> orphanReservationEvents = reservationRepository.findLegacyEventIdsWithNullSession();
            List<UUID> orphanHoldEvents = seatHoldRepository.findLegacyEventIdsWithNullSession();
            List<UUID> orphans = new ArrayList<>(orphanReservationEvents);
            for (UUID eventId : orphanHoldEvents) {
                if (!orphans.contains(eventId)) {
                    orphans.add(eventId);
                }
            }
            log.error("Fail-closed backfill gate: NULL session ids remain. reservations={}, seatHolds={}, legacyEventIds={}",
                    nullReservations, nullHolds, orphans);
            throw new IllegalStateException(
                    "Backfill incomplete: " + nullReservations + " reservation(s) and " + nullHolds
                            + " seat hold(s) still lack event_session_id for legacy event(s) " + orphans
                            + ". Refusing to proceed; supply the missing event -> session mapping.");
        }
        log.info("Session backfill gate passed: zero NULL event_session_id rows.");
    }
}
