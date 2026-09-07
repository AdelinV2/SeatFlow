package com.seatflow.analytics.projection;

import com.seatflow.analytics.model.entity.AnalyticsTicketFact;
import com.seatflow.analytics.model.entity.AnalyticsTicketRevocationFact;
import com.seatflow.analytics.repository.AnalyticsPaymentFactRepository;
import com.seatflow.analytics.repository.AnalyticsReservationFactRepository;
import com.seatflow.analytics.repository.AnalyticsTicketFactRepository;
import com.seatflow.analytics.repository.AnalyticsTicketRevocationFactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static com.seatflow.analytics.projection.ReservationProjectionHandler.earliest;
import static com.seatflow.analytics.projection.ReservationProjectionHandler.latest;
import static com.seatflow.analytics.projection.ReservationProjectionHandler.utcDate;

/**
 * Ticket fact reducer (TASK-P14-003).
 *
 * <p>Rules:
 * <ul>
 *   <li>issue fills identity/correlation and {@code issued_at}; later issues never move it;</li>
 *   <li>per-ticket revocation sets {@code revoked_at} (earliest wins) and status
 *       {@code REVOKED};</li>
 *   <li>reservation-scoped revocation (no ticket IDs) is retained in
 *       {@code analytics_ticket_revocation_facts} and applied to already-known tickets of that
 *       reservation immediately plus to later issue events at reconcile time — never dropped;</li>
 *   <li>scan evidence before issue creates a sparse row ({@code PENDING_ISSUE}) that later issue
 *       fills and reconciles; {@code first_scanned_at} is set-if-null only, so any number of
 *       scan attempts yields exactly one attendance unit;</li>
 *   <li>missing correlation resolves through the payload first, then the analytics reservation
 *       fact; unresolved facts wait instead of failing.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketProjectionHandler {

    static final String STATUS_ISSUED = "ISSUED";
    static final String STATUS_REVOKED = "REVOKED";
    static final String STATUS_PENDING_ISSUE = "PENDING_ISSUE";

    private final AnalyticsTicketFactRepository ticketFacts;
    private final AnalyticsTicketRevocationFactRepository revocationFacts;
    private final AnalyticsReservationFactRepository reservationFacts;
    private final AnalyticsPaymentFactRepository paymentFacts;
    private final EventSessionProjectionHandler sessions;

    @Transactional
    public ProjectionImpact onIssued(
            UUID ticketId, UUID reservationId, UUID payloadSessionId, UUID payloadEventId,
            Instant occurredAt) {
        AnalyticsTicketFact ticket = ticketFacts.findById(ticketId).orElseGet(() ->
                AnalyticsTicketFact.builder()
                        .ticketId(ticketId)
                        .status(STATUS_ISSUED)
                        .lastSourceEventAt(occurredAt)
                        .updatedAt(Instant.now())
                        .build());
        UUID previousSession = ticket.getEventSessionId();
        if (reservationId != null && ticket.getReservationId() == null) {
            ticket.setReservationId(reservationId);
        }
        UUID sessionId = payloadSessionId != null ? payloadSessionId : ticket.getEventSessionId();
        if (sessionId == null && ticket.getReservationId() != null) {
            sessionId = reservationFacts.findById(ticket.getReservationId())
                    .map(r -> r.getEventSessionId()).orElse(null);
        }
        if (sessionId != null) {
            ticket.setEventSessionId(sessionId);
        }
        UUID eventId = payloadEventId != null ? payloadEventId : null;
        if (eventId == null && ticket.getReservationId() != null) {
            eventId = reservationFacts.findById(ticket.getReservationId())
                    .map(r -> r.getEventId()).orElse(null);
        }
        if (ticket.getIssuedAt() == null) {
            ticket.setIssuedAt(occurredAt);
        }
        if (ticket.getRevokedAt() == null && ticket.getReservationId() != null) {
            revocationFacts.findById(ticket.getReservationId()).ifPresent(revocation -> {
                ticket.setRevokedAt(revocation.getRevokedAt());
                ticket.setStatus(STATUS_REVOKED);
                log.debug("Late ticket issue reconciled against batch revocation. ticketId={}", ticketId);
            });
        }
        if (ticket.getRevokedAt() == null && STATUS_PENDING_ISSUE.equals(ticket.getStatus())) {
            ticket.setStatus(STATUS_ISSUED);
        }
        if (eventId != null && sessionId != null) {
            touch(ticket, occurredAt);
            sessions.ensureSession(eventId, sessionId, ticket.getLastSourceEventAt());
        } else {
            touch(ticket, occurredAt);
        }
        ticketFacts.save(ticket);
        return impactFor(ticket, eventId).merge(previousTicketKeys(ticket, previousSession));
    }

    @Transactional
    public ProjectionImpact onRevoked(
            UUID ticketId, UUID reservationId, UUID payloadSessionId, UUID payloadEventId,
            String envelopeEventId, Instant occurredAt) {
        if (ticketId != null) {
            AnalyticsTicketFact ticket = ticketFacts.findById(ticketId).orElseGet(() ->
                    AnalyticsTicketFact.builder()
                            .ticketId(ticketId)
                            .reservationId(reservationId)
                            .status(STATUS_REVOKED)
                            .lastSourceEventAt(occurredAt)
                            .updatedAt(Instant.now())
                            .build());
            if (reservationId != null && ticket.getReservationId() == null) {
                ticket.setReservationId(reservationId);
            }
            touch(ticket, occurredAt);
            fillTicketCorrelation(ticket, payloadSessionId, payloadEventId);
            if (ticket.getRevokedAt() == null || occurredAt.isBefore(ticket.getRevokedAt())) {
                ticket.setRevokedAt(occurredAt);
            }
            ticket.setStatus(STATUS_REVOKED);
            ticketFacts.save(ticket);
            return impactFor(ticket, payloadEventId);
        }
        // Reservation-scoped revocation: retain evidence, apply to known tickets now.
        if (reservationId == null) {
            log.warn("Ticket revocation without ticket or reservation identity ignored.");
            return ProjectionImpact.empty();
        }
        AnalyticsTicketRevocationFact revocation = revocationFacts.findById(reservationId).orElse(null);
        if (revocation == null) {
            revocation = AnalyticsTicketRevocationFact.builder()
                    .reservationId(reservationId)
                    .eventSessionId(payloadSessionId)
                    .revokedAt(occurredAt)
                    .sourceEventId(envelopeEventId)
                    .lastSourceEventAt(occurredAt)
                    .updatedAt(Instant.now())
                    .build();
        } else {
            revocation.setRevokedAt(earliest(revocation.getRevokedAt(), occurredAt));
            if (revocation.getEventSessionId() == null && payloadSessionId != null) {
                revocation.setEventSessionId(payloadSessionId);
            }
            revocation.setLastSourceEventAt(latest(revocation.getLastSourceEventAt(), occurredAt));
            revocation.setUpdatedAt(Instant.now());
        }
        revocationFacts.save(revocation);
        ProjectionImpact.Builder impact = ProjectionImpact.builder();
        for (AnalyticsTicketFact ticket : ticketFacts.findByReservationId(reservationId)) {
            if (ticket.getRevokedAt() == null) {
                ticket.setRevokedAt(revocation.getRevokedAt());
                ticket.setStatus(STATUS_REVOKED);
                fillTicketCorrelation(ticket, payloadSessionId, payloadEventId);
                touch(ticket, occurredAt);
                ticketFacts.save(ticket);
            }
            impact.session(resolveEvent(ticket, payloadEventId), ticket.getEventSessionId());
            if (ticket.getIssuedAt() != null) {
                impact.daily(utcDate(ticket.getIssuedAt()),
                        resolveEvent(ticket, payloadEventId), ticket.getEventSessionId());
            }
            impact.daily(utcDate(ticket.getRevokedAt()),
                    resolveEvent(ticket, payloadEventId), ticket.getEventSessionId());
        }
        UUID sessionId = revocation.getEventSessionId();
        if (sessionId == null && payloadEventId != null && payloadSessionId != null) {
            sessions.ensureSession(payloadEventId, payloadSessionId, occurredAt);
            impact.session(payloadEventId, payloadSessionId);
        }
        return impact.build();
    }

    @Transactional
    public ProjectionImpact onScanned(
            UUID ticketId, UUID reservationId, UUID payloadSessionId, UUID payloadEventId,
            Instant occurredAt) {
        AnalyticsTicketFact ticket = ticketFacts.findById(ticketId).orElseGet(() ->
                AnalyticsTicketFact.builder()
                        .ticketId(ticketId)
                        .reservationId(reservationId)
                        .status(STATUS_PENDING_ISSUE)
                        .lastSourceEventAt(occurredAt)
                        .updatedAt(Instant.now())
                        .build());
        if (reservationId != null && ticket.getReservationId() == null) {
            ticket.setReservationId(reservationId);
        }
        touch(ticket, occurredAt);
        fillTicketCorrelation(ticket, payloadSessionId, payloadEventId);
        if (ticket.getFirstScannedAt() == null || occurredAt.isBefore(ticket.getFirstScannedAt())) {
            ticket.setFirstScannedAt(occurredAt);
        }
        if (ticket.getIssuedAt() == null && ticket.getRevokedAt() == null
                && !STATUS_PENDING_ISSUE.equals(ticket.getStatus())) {
            ticket.setStatus(STATUS_PENDING_ISSUE);
        }
        ticketFacts.save(ticket);
        return impactFor(ticket, payloadEventId);
    }

    private void fillTicketCorrelation(
            AnalyticsTicketFact ticket, UUID payloadSessionId, UUID payloadEventId) {
        if (payloadSessionId != null && ticket.getEventSessionId() == null) {
            ticket.setEventSessionId(payloadSessionId);
        }
        if (ticket.getEventSessionId() == null && ticket.getReservationId() != null) {
            reservationFacts.findById(ticket.getReservationId()).ifPresent(reservation -> {
                ticket.setEventSessionId(reservation.getEventSessionId());
                sessions.ensureSession(reservation.getEventId(), reservation.getEventSessionId(),
                        ticket.getLastSourceEventAt());
            });
        }
        UUID eventId = payloadEventId != null ? payloadEventId : null;
        if (eventId == null && ticket.getReservationId() != null) {
            eventId = reservationFacts.findById(ticket.getReservationId())
                    .map(r -> r.getEventId()).orElse(null);
        }
        if (eventId == null && ticket.getEventSessionId() != null) {
            // Scan-family payloads carry session identity but no parent-event identity. When
            // reservation correlation is not yet resolvable (scan consumed before held), fall
            // back to the already-known parent event for this session so the watermark below
            // still converges instead of being skipped (REV-005: 10:10 vs 11:00 race).
            eventId = sessions.sessionEventId(ticket.getEventSessionId()).orElse(null);
        }
        if (eventId != null && ticket.getEventSessionId() != null) {
            sessions.ensureSession(eventId, ticket.getEventSessionId(), correlatedWatermark(ticket));
        }
    }

    /**
     * Order-independent session watermark for one ticket's session (REV-005).
     *
     * <p>The maximum source-event time over the correlated analytics facts for that session
     * (this ticket, its reservation fact, and that reservation's payment facts). Folding the
     * max — rather than the triggering event's time — makes late correlation repair converge
     * to the same watermark regardless of cross-topic delivery order. Served freshness is
     * unaffected: APIs serve {@code latestOf(metric.lastProjectedEventAt, session.lastSourceEventAt)}
     * and the metric watermark is already reconciler-maxed.
     */
    private Instant correlatedWatermark(AnalyticsTicketFact ticket) {
        Instant watermark = ticket.getLastSourceEventAt();
        if (ticket.getReservationId() != null) {
            Instant reservationTime = reservationFacts.findById(ticket.getReservationId())
                    .map(r -> r.getLastSourceEventAt()).orElse(null);
            if (reservationTime != null) {
                watermark = latest(watermark, reservationTime);
            }
            for (var payment : paymentFacts.findByReservationId(ticket.getReservationId())) {
                if (payment.getLastSourceEventAt() != null) {
                    watermark = latest(watermark, payment.getLastSourceEventAt());
                }
            }
        }
        return watermark;
    }

    private ProjectionImpact impactFor(AnalyticsTicketFact ticket, UUID payloadEventId) {
        ProjectionImpact.Builder impact = ProjectionImpact.builder();
        UUID eventId = resolveEvent(ticket, payloadEventId);
        impact.session(eventId, ticket.getEventSessionId());
        if (ticket.getIssuedAt() != null) {
            impact.daily(utcDate(ticket.getIssuedAt()), eventId, ticket.getEventSessionId());
        }
        if (ticket.getRevokedAt() != null) {
            impact.daily(utcDate(ticket.getRevokedAt()), eventId, ticket.getEventSessionId());
        }
        if (ticket.getFirstScannedAt() != null) {
            impact.daily(utcDate(ticket.getFirstScannedAt()), eventId, ticket.getEventSessionId());
        }
        return impact.build();
    }

    private UUID resolveEvent(AnalyticsTicketFact ticket, UUID payloadEventId) {
        if (payloadEventId != null) {
            return payloadEventId;
        }
        if (ticket.getReservationId() != null) {
            UUID fromReservation = reservationFacts.findById(ticket.getReservationId())
                    .map(r -> r.getEventId()).orElse(null);
            if (fromReservation != null) {
                return fromReservation;
            }
        }
        if (ticket.getEventSessionId() != null) {
            return sessions.sessionEventId(ticket.getEventSessionId()).orElse(null);
        }
        return null;
    }

    private static void touch(AnalyticsTicketFact ticket, Instant occurredAt) {
        ticket.setLastSourceEventAt(latest(ticket.getLastSourceEventAt(), occurredAt));
        ticket.setUpdatedAt(Instant.now());
    }

    /**
     * Keys for the previously attributed session, so a valid correction moving correlation
     * A → B recomputes both buckets and leaves no stale contribution in A.
     */
    private ProjectionImpact previousTicketKeys(AnalyticsTicketFact ticket, UUID previousSession) {
        if (previousSession == null || previousSession.equals(ticket.getEventSessionId())) {
            return ProjectionImpact.empty();
        }
        UUID prevEvent = sessions.sessionEventId(previousSession)
                .orElseGet(() -> resolveEvent(ticket, null));
        ProjectionImpact.Builder extra = ProjectionImpact.builder()
                .session(prevEvent, previousSession);
        if (ticket.getIssuedAt() != null) {
            extra.daily(utcDate(ticket.getIssuedAt()), prevEvent, previousSession);
        }
        if (ticket.getRevokedAt() != null) {
            extra.daily(utcDate(ticket.getRevokedAt()), prevEvent, previousSession);
        }
        if (ticket.getFirstScannedAt() != null) {
            extra.daily(utcDate(ticket.getFirstScannedAt()), prevEvent, previousSession);
        }
        return extra.build();
    }
}
