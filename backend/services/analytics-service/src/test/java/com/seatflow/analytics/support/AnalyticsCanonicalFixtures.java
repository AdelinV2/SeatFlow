package com.seatflow.analytics.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatflow.common.events.EventEnvelope;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Canonical TASK-P14-007 integration fixtures with fixed UUIDs and fixed UTC instants.
 *
 * <p>Baseline scenario (task section 6), event names bound to the final canonical contracts:
 *
 * <ul>
 *   <li>S1/E1/RON: R1 held (2 seats) {@literal ->} P1 {@code 200.00 RON} completed
 *       {@literal ->} confirmed {@literal ->} T1/T2 issued {@literal ->} T1 scanned
 *       {@literal ->} P1 fully refunded {@code 200.00 RON} + R1 refunded + T1/T2 revoked.
 *       Expected: gross 20000, refunded 20000, net 0, 1 payment, 1 refund, 2 issued,
 *       2 revoked, 1 scanned, reservation created + confirmed + refunded.</li>
 *   <li>S2/E1/RON: R2 held then expired, no payment/ticket.
 *       Expected: created +1, expired +1, gross +0.</li>
 *   <li>S3/E2/EUR: R3 held/confirmed, P3 {@code 50.00 EUR}, one ticket issued + scanned.
 *       Expected: EUR gross 5000.</li>
 *   <li>S4/E3 multi-currency grain: R4 + P4 {@code 100.00 RON} and R5 + P5
 *       {@code 20.00 EUR} on the SAME S4, same UTC date. Expected: exactly 1
 *       {@code event_session_metrics} row (created 2, confirmed 2, payments 2, issued 2),
 *       exactly 2 revenue rows (RON 10000, EUR 2000), no 12000 mixed total, no count 4.</li>
 *   <li>P6 failure evidence: canonical failure for a reservation, no completion.
 *       Expected: {@code payments_with_failure} +1, gross unchanged.</li>
 * </ul>
 *
 * <p>Timestamps span 2026-09-05 and 2026-09-06 UTC so daily bucket and inclusive
 * range behavior is exercised. All money assertions use exact integer minor units.
 *
 * <h2>Producer-binding status (REV-001, honest scope)</h2>
 * <p>Histories for held/confirmed/expired/failed/completed/issued/lifecycle events mirror
 * implemented producer outbox serialization; binding is proven by
 * {@code AnalyticsProducerEnvelopeContractTest} and the producer-side analytics-field
 * assertions. The S1 refund + revocation tail and the accepted-scan steps are
 * <em>projection-reducer coverage</em> for P14-003-owned future contracts: Phase 13 is
 * {@code PLANNED} (no refund/revocation producer exists) and ticket scanning is synchronous
 * REST only ({@code TicketServiceImpl.validateTicket} writes {@code USED} + audit, no outbox).
 * Expected S1 facts (gross 20000 / refunded 20000 / net 0 / 2 revoked / 1 scanned) therefore
 * prove reducer semantics, not end-to-end producer behavior; rebind them to real producer
 * serialization when Phase 13 lands (see review ledger {@code REV-001}).
 */
public final class AnalyticsCanonicalFixtures {

    // -- parent events / sessions -------------------------------------------

    public static final UUID EVENT_E1 = UUID.fromString("e1e1e1e1-1111-4111-8111-111111111111");
    public static final UUID EVENT_E2 = UUID.fromString("e2e2e2e2-2222-4222-8222-222222222222");
    public static final UUID EVENT_E3 = UUID.fromString("e3e3e3e3-3333-4333-8333-333333333333");

    public static final UUID S1 = UUID.fromString("11111111-1111-4111-8111-111111111111");
    public static final UUID S2 = UUID.fromString("22222222-2222-4222-8222-222222222222");
    public static final UUID S3 = UUID.fromString("33333333-3333-4333-8333-333333333333");
    public static final UUID S4 = UUID.fromString("44444444-4444-4444-8444-444444444444");
    /** Dedicated session for the P6 failure fixture, so S1 expectations stay exact. */
    public static final UUID S5 = UUID.fromString("55555555-5555-4555-8555-555555555555");

    // -- reservations / payments / tickets -----------------------------------

    public static final UUID R1 = UUID.fromString("a1111111-1111-4111-8111-111111111111");
    public static final UUID R2 = UUID.fromString("a2222222-2222-4222-8222-222222222222");
    public static final UUID R3 = UUID.fromString("a3333333-3333-4333-8333-333333333333");
    public static final UUID R4 = UUID.fromString("a4444444-4444-4444-8444-444444444444");
    public static final UUID R5 = UUID.fromString("a5555555-5555-4555-8555-555555555555");
    public static final UUID R6 = UUID.fromString("a6666666-6666-4666-8666-666666666666");

    public static final UUID P1 = UUID.fromString("b1111111-1111-4111-8111-111111111111");
    public static final UUID P3 = UUID.fromString("b3333333-3333-4333-8333-333333333333");
    public static final UUID P4 = UUID.fromString("b4444444-4444-4444-8444-444444444444");
    public static final UUID P5 = UUID.fromString("b5555555-5555-4555-8555-555555555555");
    public static final UUID P6 = UUID.fromString("b6666666-6666-4666-8666-666666666666");

    public static final UUID T1 = UUID.fromString("c1111111-1111-4111-8111-111111111111");
    public static final UUID T2 = UUID.fromString("c2222222-2222-4222-8222-222222222222");
    public static final UUID T3 = UUID.fromString("c3333333-3333-4333-8333-333333333333");
    public static final UUID T4 = UUID.fromString("c4444444-4444-4444-8444-444444444444");
    public static final UUID T5 = UUID.fromString("c5555555-5555-4555-8555-555555555555");

    // -- fixed UTC business times (two dates) --------------------------------

    /** 2026-09-05: S1 lifecycle + S2 expiry live here. */
    public static final Instant D1_HOLD = Instant.parse("2026-09-05T10:00:00Z");
    public static final Instant D1_PAY = Instant.parse("2026-09-05T10:05:00Z");
    public static final Instant D1_CONFIRM = Instant.parse("2026-09-05T10:06:00Z");
    public static final Instant D1_ISSUE = Instant.parse("2026-09-05T10:10:00Z");
    public static final Instant D1_SCAN = Instant.parse("2026-09-05T11:00:00Z");
    public static final Instant D1_EXPIRE = Instant.parse("2026-09-05T12:00:00Z");

    /** 2026-09-06: refunds, S3, S4 grain, and P6 failure live here. */
    public static final Instant D2_REFUND = Instant.parse("2026-09-06T09:00:00Z");
    public static final Instant D2_RREFUND = Instant.parse("2026-09-06T09:01:00Z");
    public static final Instant D2_REVOKE = Instant.parse("2026-09-06T09:02:00Z");
    public static final Instant D2_S3_HOLD = Instant.parse("2026-09-06T10:00:00Z");
    public static final Instant D2_S3_PAY = Instant.parse("2026-09-06T10:05:00Z");
    public static final Instant D2_S3_CONFIRM = Instant.parse("2026-09-06T10:06:00Z");
    public static final Instant D2_S3_ISSUE = Instant.parse("2026-09-06T10:10:00Z");
    public static final Instant D2_S3_SCAN = Instant.parse("2026-09-06T11:00:00Z");
    public static final Instant D2_S4_HOLD = Instant.parse("2026-09-06T12:00:00Z");
    public static final Instant D2_S4_PAY = Instant.parse("2026-09-06T12:05:00Z");
    public static final Instant D2_S4_CONFIRM = Instant.parse("2026-09-06T12:06:00Z");
    public static final Instant D2_S4_ISSUE = Instant.parse("2026-09-06T12:10:00Z");
    public static final Instant D2_P6_HOLD = Instant.parse("2026-09-06T13:00:00Z");
    public static final Instant D2_P6_FAIL = Instant.parse("2026-09-06T13:05:00Z");

    // -- expected minor-unit facts --------------------------------------------

    public static final long S1_GROSS_MINOR = 20_000L;
    public static final long S1_REFUNDED_MINOR = 20_000L;
    public static final long S1_NET_MINOR = 0L;
    public static final long S3_GROSS_MINOR = 5_000L;
    public static final long S4_RON_GROSS_MINOR = 10_000L;
    public static final long S4_EUR_GROSS_MINOR = 2_000L;

    private AnalyticsCanonicalFixtures() {
    }

    // ------------------------------------------------------------------
    // Per-session histories (each list is independently replayable)
    // ------------------------------------------------------------------

    /** S1 full lifecycle incl. refund + revocations. */
    public static List<EventEnvelope<JsonNode>> s1History() {
        List<EventEnvelope<JsonNode>> history = new ArrayList<>();
        history.add(AnalyticsEnvelopeFactory.held("p14-007-s1-held", R1, EVENT_E1, S1, 2, D1_HOLD));
        history.add(AnalyticsEnvelopeFactory.completed(
                "p14-007-s1-pay", P1, R1, S1, EVENT_E1, "200.00", "RON", D1_PAY));
        history.add(AnalyticsEnvelopeFactory.confirmed(
                "p14-007-s1-confirmed", R1, EVENT_E1, S1, P1, D1_CONFIRM));
        history.add(AnalyticsEnvelopeFactory.issued(
                "p14-007-s1-issued-t1", T1, R1, S1, EVENT_E1, D1_ISSUE));
        history.add(AnalyticsEnvelopeFactory.issued(
                "p14-007-s1-issued-t2", T2, R1, S1, EVENT_E1, D1_ISSUE.plusSeconds(5)));
        history.add(AnalyticsEnvelopeFactory.scanned(
                "p14-007-s1-scanned-t1", T1, R1, S1, D1_SCAN));
        history.add(AnalyticsEnvelopeFactory.paymentRefunded(
                "p14-007-s1-payment-refunded", P1, R1, S1, EVENT_E1, "200.00", "RON", D2_REFUND));
        history.add(AnalyticsEnvelopeFactory.reservationRefunded(
                "p14-007-s1-reservation-refunded", R1, EVENT_E1, S1, D2_RREFUND));
        history.add(AnalyticsEnvelopeFactory.revokedForTicket(
                "p14-007-s1-revoked-t1", T1, R1, S1, EVENT_E1, D2_REVOKE));
        history.add(AnalyticsEnvelopeFactory.revokedForTicket(
                "p14-007-s1-revoked-t2", T2, R1, S1, EVENT_E1, D2_REVOKE.plusSeconds(5)));
        return List.copyOf(history);
    }

    /** S2: held then expired, no payment or ticket. */
    public static List<EventEnvelope<JsonNode>> s2History() {
        return List.of(
                AnalyticsEnvelopeFactory.held("p14-007-s2-held", R2, EVENT_E1, S2, 1, D1_HOLD),
                AnalyticsEnvelopeFactory.expired("p14-007-s2-expired", R2, EVENT_E1, S2, D1_EXPIRE));
    }

    /** S3/EUR single purchase with one issued + scanned ticket. */
    public static List<EventEnvelope<JsonNode>> s3History() {
        return List.of(
                AnalyticsEnvelopeFactory.held("p14-007-s3-held", R3, EVENT_E2, S3, 1, D2_S3_HOLD),
                AnalyticsEnvelopeFactory.completed(
                        "p14-007-s3-pay", P3, R3, S3, EVENT_E2, "50.00", "EUR", D2_S3_PAY),
                AnalyticsEnvelopeFactory.confirmed(
                        "p14-007-s3-confirmed", R3, EVENT_E2, S3, P3, D2_S3_CONFIRM),
                AnalyticsEnvelopeFactory.issued(
                        "p14-007-s3-issued", T3, R3, S3, EVENT_E2, D2_S3_ISSUE),
                AnalyticsEnvelopeFactory.scanned(
                        "p14-007-s3-scanned", T3, R3, S3, D2_S3_SCAN));
    }

    /** S4 same-session RON+EUR grain fixture (no refunds). */
    public static List<EventEnvelope<JsonNode>> s4History() {
        List<EventEnvelope<JsonNode>> history = new ArrayList<>();
        history.add(AnalyticsEnvelopeFactory.held("p14-007-s4-held-r4", R4, EVENT_E3, S4, 1, D2_S4_HOLD));
        history.add(AnalyticsEnvelopeFactory.completed(
                "p14-007-s4-pay-r4", P4, R4, S4, EVENT_E3, "100.00", "RON", D2_S4_PAY));
        history.add(AnalyticsEnvelopeFactory.confirmed(
                "p14-007-s4-confirmed-r4", R4, EVENT_E3, S4, P4, D2_S4_CONFIRM));
        history.add(AnalyticsEnvelopeFactory.issued(
                "p14-007-s4-issued-r4", T4, R4, S4, EVENT_E3, D2_S4_ISSUE));
        history.add(AnalyticsEnvelopeFactory.held(
                "p14-007-s4-held-r5", R5, EVENT_E3, S4, 1, D2_S4_HOLD.plusSeconds(30)));
        history.add(AnalyticsEnvelopeFactory.completed(
                "p14-007-s4-pay-r5", P5, R5, S4, EVENT_E3, "20.00", "EUR", D2_S4_PAY.plusSeconds(30)));
        history.add(AnalyticsEnvelopeFactory.confirmed(
                "p14-007-s4-confirmed-r5", R5, EVENT_E3, S4, P5, D2_S4_CONFIRM.plusSeconds(30)));
        history.add(AnalyticsEnvelopeFactory.issued(
                "p14-007-s4-issued-r5", T5, R5, S4, EVENT_E3, D2_S4_ISSUE.plusSeconds(30)));
        return List.copyOf(history);
    }

    /** P6 failure evidence with no successful completion (isolated S5 session). */
    public static List<EventEnvelope<JsonNode>> failureHistory() {
        return List.of(
                AnalyticsEnvelopeFactory.held("p14-007-p6-held", R6, EVENT_E1, S5, 1, D2_P6_HOLD),
                AnalyticsEnvelopeFactory.failed("p14-007-p6-failed", P6, R6, S5, EVENT_E1, D2_P6_FAIL));
    }

    /** Full retained baseline: S1 + S2 + S3 + S4 + P6 failure. */
    public static List<EventEnvelope<JsonNode>> fullBaseline() {
        List<EventEnvelope<JsonNode>> all = new ArrayList<>();
        all.addAll(s1History());
        all.addAll(s2History());
        all.addAll(s3History());
        all.addAll(s4History());
        all.addAll(failureHistory());
        return List.copyOf(all);
    }

    /** Same retained set with per-session blocks reordered (S4 first) for rebuild checks. */
    public static List<EventEnvelope<JsonNode>> fullBaselineSessionReordered() {
        List<EventEnvelope<JsonNode>> all = new ArrayList<>();
        all.addAll(s4History());
        all.addAll(s3History());
        all.addAll(s2History());
        all.addAll(s1History());
        all.addAll(failureHistory());
        return List.copyOf(all);
    }
}
