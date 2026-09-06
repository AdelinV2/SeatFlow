package com.seatflow.analytics.projection;

import com.seatflow.analytics.model.entity.AnalyticsReservationFact;
import com.seatflow.analytics.repository.AnalyticsPaymentFactRepository;
import com.seatflow.analytics.repository.AnalyticsReservationFactRepository;
import com.seatflow.analytics.repository.AnalyticsSessionFactRepository;
import com.seatflow.analytics.repository.AnalyticsTicketFactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Reservation fact reducer (TASK-P14-003).
 *
 * <p>Rules:
 * <ul>
 *   <li>creation/hold initializes identity, session, seat count, and {@code created_at};</li>
 *   <li>confirmation sets {@code confirmed_at} once (earliest trusted occurrence wins);</li>
 *   <li>expiration sets {@code expired_at} only when no later valid confirmation/refund/
 *       cancellation evidence exists — a stale expiration replay never erases them;</li>
 *   <li>cancellation sets {@code cancelled_at} once (earliest wins) and is terminal;</li>
 *   <li>completed refund sets {@code refunded_at} once (earliest wins);</li>
 *   <li>{@code last_source_event_at} is the max envelope time seen (order-independent);</li>
 *   <li>when reservation correlation becomes known, unresolved payment/ticket facts for that
 *       reservation are backfilled so payment-before-reservation converges.</li>
 * </ul>
 *
 * <p>All timestamps are business-event time (payload {@code occurredAt} when present, else the
 * envelope time); processing time is never substituted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationProjectionHandler {

    private final AnalyticsReservationFactRepository reservationFacts;
    private final AnalyticsPaymentFactRepository paymentFacts;
    private final AnalyticsTicketFactRepository ticketFacts;
    private final AnalyticsSessionFactRepository sessionFacts;
    private final EventSessionProjectionHandler sessions;

    @Transactional
    public ProjectionImpact onHeld(
            UUID reservationId, UUID eventId, UUID eventSessionId, int seatCount, Instant occurredAt) {
        AnalyticsReservationFact existing = reservationFacts.findById(reservationId).orElse(null);
        UUID previousSession = existing != null ? existing.getEventSessionId() : null;
        AnalyticsReservationFact fact = existing;
        if (fact == null) {
            fact = AnalyticsReservationFact.builder()
                    .reservationId(reservationId)
                    .eventId(eventId)
                    .eventSessionId(eventSessionId)
                    .createdAt(occurredAt)
                    .seatCount(seatCount)
                    .lastSourceEventAt(occurredAt)
                    .updatedAt(Instant.now())
                    .build();
        } else if (!hasTerminalEvidence(fact)) {
            // No confirmation/expiry/refund/cancellation evidence yet: held is authoritative
            // creation evidence (also repairs a refund-first sparse row once held arrives).
            fact.setCreatedAt(earliest(fact.getCreatedAt(), occurredAt));
            fact.setSeatCount(seatCount);
            fact.setLastSourceEventAt(latest(fact.getLastSourceEventAt(), occurredAt));
        } else {
            fact.setLastSourceEventAt(latest(fact.getLastSourceEventAt(), occurredAt));
        }
        fact.setUpdatedAt(Instant.now());
        reservationFacts.save(fact);

        sessions.ensureSession(eventId, eventSessionId, occurredAt);
        ProjectionImpact.Builder impact = ProjectionImpact.builder()
                .session(eventId, eventSessionId)
                .daily(utcDate(fact.getCreatedAt()), fact.getEventId(), fact.getEventSessionId());
        if (previousSession != null && !previousSession.equals(fact.getEventSessionId())) {
            UUID previousEvent = sessions.sessionEventId(previousSession).orElse(null);
            impact.session(previousEvent, previousSession)
                    .daily(utcDate(fact.getCreatedAt()), previousEvent, previousSession);
        }
        backfillPayments(fact, impact);
        backfillTickets(fact, impact);
        return impact.build();
    }

    @Transactional
    public ProjectionImpact onConfirmed(
            UUID reservationId, UUID eventId, UUID eventSessionId, Instant occurredAt) {
        AnalyticsReservationFact fact = requireOrSparse(reservationId, eventId, eventSessionId, occurredAt);
        if (fact.getConfirmedAt() == null || occurredAt.isBefore(fact.getConfirmedAt())) {
            fact.setConfirmedAt(occurredAt);
        }
        touch(fact, occurredAt);
        reservationFacts.save(fact);
        return impactFor(fact);
    }

    @Transactional
    public ProjectionImpact onExpired(
            UUID reservationId, UUID eventId, UUID eventSessionId, Instant occurredAt) {
        AnalyticsReservationFact fact = requireOrSparse(reservationId, eventId, eventSessionId, occurredAt);
        if (fact.getConfirmedAt() == null && fact.getRefundedAt() == null && fact.getCancelledAt() == null
                && (fact.getExpiredAt() == null || occurredAt.isBefore(fact.getExpiredAt()))) {
            fact.setExpiredAt(occurredAt);
        } else {
            log.debug("Stale reservation expiration ignored. reservationId={}", reservationId);
        }
        touch(fact, occurredAt);
        reservationFacts.save(fact);
        return impactFor(fact);
    }

    @Transactional
    public ProjectionImpact onCancelled(
            UUID reservationId, UUID eventId, UUID eventSessionId, Instant occurredAt) {
        AnalyticsReservationFact fact = requireOrSparse(reservationId, eventId, eventSessionId, occurredAt);
        if (fact.getCancelledAt() == null || occurredAt.isBefore(fact.getCancelledAt())) {
            fact.setCancelledAt(occurredAt);
        }
        touch(fact, occurredAt);
        reservationFacts.save(fact);
        return impactFor(fact);
    }

    @Transactional
    public ProjectionImpact onRefunded(
            UUID reservationId, UUID eventId, UUID eventSessionId, Instant occurredAt) {
        AnalyticsReservationFact fact = requireOrSparse(reservationId, eventId, eventSessionId, occurredAt);
        if (fact.getRefundedAt() == null || occurredAt.isBefore(fact.getRefundedAt())) {
            fact.setRefundedAt(occurredAt);
        }
        touch(fact, occurredAt);
        reservationFacts.save(fact);
        return impactFor(fact);
    }

    private AnalyticsReservationFact requireOrSparse(
            UUID reservationId, UUID eventId, UUID eventSessionId, Instant occurredAt) {
        AnalyticsReservationFact fact = reservationFacts.findById(reservationId).orElse(null);
        if (fact == null) {
            // Valid lifecycle event before held (same-partition ordering makes this exceptional):
            // retain a sparse row so the event is never dropped; held arrival repairs identity.
            fact = AnalyticsReservationFact.builder()
                    .reservationId(reservationId)
                    .eventId(eventId)
                    .eventSessionId(eventSessionId)
                    .createdAt(occurredAt)
                    .seatCount(1)
                    .lastSourceEventAt(occurredAt)
                    .updatedAt(Instant.now())
                    .build();
            sessions.ensureSession(eventId, eventSessionId, occurredAt);
        }
        return fact;
    }

    private void backfillPayments(AnalyticsReservationFact fact, ProjectionImpact.Builder impact) {
        paymentFacts.findByReservationId(fact.getReservationId()).stream()
                .filter(p -> p.getEventSessionId() == null)
                .forEach(p -> {
                    p.setEventSessionId(fact.getEventSessionId());
                    p.setLastSourceEventAt(latest(p.getLastSourceEventAt(), fact.getLastSourceEventAt()));
                    p.setUpdatedAt(Instant.now());
                    paymentFacts.save(p);
                    impact.session(fact.getEventId(), fact.getEventSessionId());
                    if (p.getCompletedAt() != null) {
                        impact.daily(utcDate(p.getCompletedAt()), fact.getEventId(), fact.getEventSessionId());
                        impact.sessionRevenue(fact.getEventSessionId(), p.getCurrency());
                        impact.dailyRevenue(utcDate(p.getCompletedAt()), fact.getEventId(),
                                fact.getEventSessionId(), p.getCurrency());
                    }
                    if (p.getFailedAt() != null) {
                        impact.daily(utcDate(p.getFailedAt()), fact.getEventId(), fact.getEventSessionId());
                    }
                    if (p.getCompletedAt() != null && p.getRefundedAt() != null) {
                        impact.daily(utcDate(p.getRefundedAt()), fact.getEventId(), fact.getEventSessionId());
                        impact.dailyRevenue(utcDate(p.getRefundedAt()), fact.getEventId(),
                                fact.getEventSessionId(), p.getCurrency());
                    }
                });
    }

    private void backfillTickets(AnalyticsReservationFact fact, ProjectionImpact.Builder impact) {
        ticketFacts.findByReservationId(fact.getReservationId()).stream()
                .filter(t -> t.getEventSessionId() == null)
                .forEach(t -> {
                    t.setEventSessionId(fact.getEventSessionId());
                    t.setLastSourceEventAt(latest(t.getLastSourceEventAt(), fact.getLastSourceEventAt()));
                    t.setUpdatedAt(Instant.now());
                    ticketFacts.save(t);
                    impact.session(fact.getEventId(), fact.getEventSessionId());
                    if (t.getIssuedAt() != null) {
                        impact.daily(utcDate(t.getIssuedAt()), fact.getEventId(), fact.getEventSessionId());
                    }
                    if (t.getRevokedAt() != null) {
                        impact.daily(utcDate(t.getRevokedAt()), fact.getEventId(), fact.getEventSessionId());
                    }
                    if (t.getFirstScannedAt() != null) {
                        impact.daily(utcDate(t.getFirstScannedAt()), fact.getEventId(), fact.getEventSessionId());
                    }
                });
    }

    private ProjectionImpact impactFor(AnalyticsReservationFact fact) {
        sessions.ensureSession(fact.getEventId(), fact.getEventSessionId(), fact.getLastSourceEventAt());
        ProjectionImpact.Builder impact = ProjectionImpact.builder()
                .session(fact.getEventId(), fact.getEventSessionId())
                .daily(utcDate(fact.getCreatedAt()), fact.getEventId(), fact.getEventSessionId());
        if (fact.getConfirmedAt() != null) {
            impact.daily(utcDate(fact.getConfirmedAt()), fact.getEventId(), fact.getEventSessionId());
        }
        if (fact.getExpiredAt() != null) {
            impact.daily(utcDate(fact.getExpiredAt()), fact.getEventId(), fact.getEventSessionId());
        }
        if (fact.getRefundedAt() != null) {
            impact.daily(utcDate(fact.getRefundedAt()), fact.getEventId(), fact.getEventSessionId());
        }
        if (fact.getCancelledAt() != null) {
            impact.daily(utcDate(fact.getCancelledAt()), fact.getEventId(), fact.getEventSessionId());
        }
        // Confirmation/refund can resolve previously uncorrelated payment evidence.
        backfillPayments(fact, impact);
        backfillTickets(fact, impact);
        return impact.build();
    }

    private static boolean hasTerminalEvidence(AnalyticsReservationFact fact) {
        return fact.getConfirmedAt() != null || fact.getExpiredAt() != null
                || fact.getRefundedAt() != null || fact.getCancelledAt() != null;
    }

    private static void touch(AnalyticsReservationFact fact, Instant occurredAt) {
        fact.setLastSourceEventAt(latest(fact.getLastSourceEventAt(), occurredAt));
        fact.setUpdatedAt(Instant.now());
    }

    static Instant latest(Instant current, Instant candidate) {
        if (current == null) {
            return candidate;
        }
        return candidate.isAfter(current) ? candidate : current;
    }

    static Instant earliest(Instant current, Instant candidate) {
        if (current == null) {
            return candidate;
        }
        return candidate.isBefore(current) ? candidate : current;
    }

    static LocalDate utcDate(Instant value) {
        return value.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
