package com.seatflow.analytics.projection;

import com.seatflow.analytics.messaging.AnalyticsEventValidationException;
import com.seatflow.analytics.model.entity.AnalyticsPaymentFact;
import com.seatflow.analytics.repository.AnalyticsPaymentFactRepository;
import com.seatflow.analytics.repository.AnalyticsReservationFactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static com.seatflow.analytics.projection.ReservationProjectionHandler.latest;
import static com.seatflow.analytics.projection.ReservationProjectionHandler.utcDate;

/**
 * Payment fact reducer (TASK-P14-003).
 *
 * <p>Rules:
 * <ul>
 *   <li>completion, failure, and refund evidence are preserved independently — a payment that
 *       failed and later succeeded counts in both historical evidence cohorts;</li>
 *   <li>a refund observed before its completion is stored provisionally and contributes to
 *       <em>no</em> financial aggregate until completion validates it;</li>
 *   <li>once both amounts are known, {@code refunded <= completed} is enforced; violations fail
 *       the event (DLQ path) and the unresolved fact stays excluded — never clamped, never
 *       negative;</li>
 *   <li>a currency change across completion/refund evidence for one payment identity is a
 *       contract violation and fails the event rather than moving money between buckets;</li>
 *   <li>session correlation prefers the event payload, then falls back to the analytics
 *       reservation fact; unresolved facts wait for the reservation instead of failing;</li>
 *   <li>completion amount overwrites on newer-or-equal source time (corrections converge;
 *       same retained history replays identically).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentProjectionHandler {

    private final AnalyticsPaymentFactRepository paymentFacts;
    private final AnalyticsReservationFactRepository reservationFacts;
    private final EventSessionProjectionHandler sessions;

    @Transactional
    public ProjectionImpact onCompleted(
            String eventType, String envelopeEventId,
            UUID paymentId, UUID reservationId, UUID payloadSessionId, UUID payloadEventId,
            long amountMinor, String currency, Instant occurredAt) {
        AnalyticsPaymentFact fact = loadOrCreate(paymentId, reservationId, occurredAt);
        UUID previousSession = fact.getEventSessionId();
        guardCurrency(fact, currency, eventType, envelopeEventId);
        if (fact.getCompletedAt() == null || !occurredAt.isBefore(fact.getCompletedAt())) {
            fact.setCompletedAmountMinor(amountMinor);
            fact.setCompletedAt(occurredAt);
            fact.setCurrency(currency);
        }
        if (fact.getRefundedAmountMinor() != null && fact.getCompletedAmountMinor() != null
                && fact.getRefundedAmountMinor() > fact.getCompletedAmountMinor()) {
            throw new AnalyticsEventValidationException(envelopeEventId, eventType,
                    "Provisional refund " + fact.getRefundedAmountMinor()
                            + " exceeds completed amount " + fact.getCompletedAmountMinor()
                            + " for payment " + paymentId);
        }
        fact.setLatestStatus(fact.getRefundedAt() != null ? "REFUNDED" : "COMPLETED");
        touch(fact, occurredAt);
        resolveSession(fact, payloadSessionId, payloadEventId);
        paymentFacts.save(fact);
        return impactFor(fact).merge(previousKeys(fact, previousSession));
    }

    @Transactional
    public ProjectionImpact onFailed(
            UUID paymentId, UUID reservationId, UUID payloadSessionId, UUID payloadEventId,
            Instant occurredAt) {
        AnalyticsPaymentFact fact = loadOrCreate(paymentId, reservationId, occurredAt);
        UUID previousSession = fact.getEventSessionId();
        if (fact.getFailedAt() == null || occurredAt.isBefore(fact.getFailedAt())) {
            fact.setFailedAt(occurredAt);
        }
        if (fact.getLatestStatus() == null) {
            fact.setLatestStatus("FAILED");
        }
        touch(fact, occurredAt);
        resolveSession(fact, payloadSessionId, payloadEventId);
        paymentFacts.save(fact);
        return impactFor(fact).merge(previousKeys(fact, previousSession));
    }

    @Transactional
    public ProjectionImpact onRefunded(
            String eventType, String envelopeEventId,
            UUID paymentId, UUID reservationId, UUID payloadSessionId, UUID payloadEventId,
            long refundMinor, String currency, Instant occurredAt) {
        AnalyticsPaymentFact fact = loadOrCreate(paymentId, reservationId, occurredAt);
        UUID previousSession = fact.getEventSessionId();
        guardCurrency(fact, currency, eventType, envelopeEventId);
        if (fact.getCompletedAt() != null) {
            long completed = fact.getCompletedAmountMinor() == null ? 0L : fact.getCompletedAmountMinor();
            if (refundMinor > completed) {
                throw new AnalyticsEventValidationException(envelopeEventId, eventType,
                        "Refund " + refundMinor + " exceeds completed amount " + completed
                                + " for payment " + paymentId);
            }
        } else {
            log.debug("Provisional refund retained without completion. paymentId={}", paymentId);
        }
        if (fact.getRefundedAt() == null || occurredAt.isBefore(fact.getRefundedAt())) {
            fact.setRefundedAmountMinor(refundMinor);
            fact.setRefundedAt(occurredAt);
            if (fact.getCurrency() == null) {
                fact.setCurrency(currency);
            }
        }
        fact.setLatestStatus(fact.getCompletedAt() != null ? "REFUNDED" : "REFUND_PENDING_COMPLETION");
        touch(fact, occurredAt);
        resolveSession(fact, payloadSessionId, payloadEventId);
        paymentFacts.save(fact);
        return impactFor(fact).merge(previousKeys(fact, previousSession));
    }

    private AnalyticsPaymentFact loadOrCreate(UUID paymentId, UUID reservationId, Instant occurredAt) {
        return paymentFacts.findById(paymentId).orElseGet(() -> AnalyticsPaymentFact.builder()
                .paymentId(paymentId)
                .reservationId(reservationId)
                .lastSourceEventAt(occurredAt)
                .updatedAt(Instant.now())
                .build());
    }

    private void guardCurrency(
            AnalyticsPaymentFact fact, String currency, String eventType, String envelopeEventId) {
        if (fact.getCurrency() != null && !fact.getCurrency().equals(currency)) {
            throw new AnalyticsEventValidationException(envelopeEventId, eventType,
                    "Payment currency changed from " + fact.getCurrency() + " to " + currency
                            + " for payment " + fact.getPaymentId());
        }
    }

    private void resolveSession(
            AnalyticsPaymentFact fact, UUID payloadSessionId, UUID payloadEventId) {
        var reservation = reservationFacts.findById(fact.getReservationId()).orElse(null);
        if (payloadSessionId != null) {
            fact.setEventSessionId(payloadSessionId);
        } else if (fact.getEventSessionId() == null && reservation != null) {
            fact.setEventSessionId(reservation.getEventSessionId());
        }
        UUID eventId = payloadEventId != null ? payloadEventId
                : reservation != null ? reservation.getEventId() : null;
        if (fact.getEventSessionId() != null && eventId != null) {
            sessions.ensureSession(eventId, fact.getEventSessionId(), fact.getLastSourceEventAt());
        }
    }

    private ProjectionImpact impactFor(AnalyticsPaymentFact fact) {
        ProjectionImpact.Builder impact = ProjectionImpact.builder();
        UUID sessionId = fact.getEventSessionId();
        UUID eventId = sessionEventId(sessionId, fact);
        impact.session(eventId, sessionId);
        if (fact.getCompletedAt() != null) {
            impact.daily(utcDate(fact.getCompletedAt()), eventId, sessionId);
            impact.sessionRevenue(sessionId, fact.getCurrency());
            impact.dailyRevenue(utcDate(fact.getCompletedAt()), eventId, sessionId, fact.getCurrency());
        }
        if (fact.getFailedAt() != null) {
            impact.daily(utcDate(fact.getFailedAt()), eventId, sessionId);
        }
        if (fact.getCompletedAt() != null && fact.getRefundedAt() != null) {
            impact.daily(utcDate(fact.getRefundedAt()), eventId, sessionId);
            impact.sessionRevenue(sessionId, fact.getCurrency());
            impact.dailyRevenue(utcDate(fact.getRefundedAt()), eventId, sessionId, fact.getCurrency());
        }
        return impact.build();
    }

    private UUID sessionEventId(UUID sessionId, AnalyticsPaymentFact fact) {
        if (sessionId == null) {
            return null;
        }
        return reservationFacts.findById(fact.getReservationId())
                .map(r -> r.getEventId())
                .orElseGet(() -> sessions.sessionEventId(sessionId).orElse(null));
    }

    /**
     * Keys for the previously attributed session, so a valid correction moving correlation
     * A → B recomputes both buckets and leaves no stale contribution in A.
     */
    private ProjectionImpact previousKeys(AnalyticsPaymentFact fact, UUID previousSession) {
        if (previousSession == null || previousSession.equals(fact.getEventSessionId())) {
            return ProjectionImpact.empty();
        }
        UUID previousEvent = sessions.sessionEventId(previousSession).orElse(null);
        ProjectionImpact.Builder extra = ProjectionImpact.builder()
                .session(previousEvent, previousSession);
        if (fact.getCompletedAt() != null) {
            extra.daily(utcDate(fact.getCompletedAt()), previousEvent, previousSession);
            extra.sessionRevenue(previousSession, fact.getCurrency());
            extra.dailyRevenue(utcDate(fact.getCompletedAt()), previousEvent, previousSession,
                    fact.getCurrency());
        }
        if (fact.getFailedAt() != null) {
            extra.daily(utcDate(fact.getFailedAt()), previousEvent, previousSession);
        }
        if (fact.getCompletedAt() != null && fact.getRefundedAt() != null) {
            extra.daily(utcDate(fact.getRefundedAt()), previousEvent, previousSession);
            extra.dailyRevenue(utcDate(fact.getRefundedAt()), previousEvent, previousSession,
                    fact.getCurrency());
        }
        return extra.build();
    }

    private static void touch(AnalyticsPaymentFact fact, Instant occurredAt) {
        fact.setLastSourceEventAt(latest(fact.getLastSourceEventAt(), occurredAt));
        fact.setUpdatedAt(Instant.now());
    }
}
