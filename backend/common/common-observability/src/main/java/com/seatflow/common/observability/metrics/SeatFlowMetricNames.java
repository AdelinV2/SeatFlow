package com.seatflow.common.observability.metrics;

/**
 * Centralized Micrometer meter name registry for SeatFlow.
 * <p>
 * All business and outbox metrics must reference these constants to guarantee
 * consistent naming across services and to keep Prometheus queries stable.
 * Micrometer timers automatically expose the {@code _seconds} and {@code _bucket}
 * suffixes in Prometheus exposition format.
 */
public final class SeatFlowMetricNames {

    private SeatFlowMetricNames() {}

    // --- Reservation domain ---
    /**
     * Deliberately omits the counter suffix. Micrometer appends {@code _total};
     * Prometheus/OpenMetrics reserves a counter family ending in {@code _created}.
     * The standards-compliant source series therefore uses {@code _events}; the
     * Prometheus scrape configuration relabels it to SeatFlow's historical
     * dashboard contract {@code seatflow_reservations_created_total} on ingest.
     */
    public static final String RESERVATIONS_CREATED = "seatflow.reservations.created.events";
    public static final String RESERVATIONS_CONFLICTS = "seatflow.reservations.conflicts.total";
    public static final String RESERVATIONS_HOLD_DURATION = "seatflow.reservations.hold.duration";
    public static final String RESERVATIONS_HOLD_DURATION_SECONDS = "seatflow.reservations.hold.duration.seconds";
    public static final String RESERVATIONS_EXPIRED = "seatflow.reservations.expired.total";
    public static final String RESERVATIONS_ACTIVE_HOLDS = "seatflow.reservations.active.holds";
    /** Legacy aliases still queried by Grafana dashboards — kept for backwards compatibility. */
    public static final String RESERVATIONS_CANCELLED = "seatflow.reservations.cancelled.total";
    public static final String RESERVATIONS_CONFIRMED = "seatflow.reservations.confirmed.total";

    // --- Payment domain ---
    public static final String PAYMENTS_PROCESSED = "seatflow.payments.processed.total";
    public static final String PAYMENTS_INTENT_CREATED = "seatflow.payments.intent.created.total";
    public static final String PAYMENTS_COMPLETED = "seatflow.payments.completed.total";
    public static final String PAYMENTS_FAILED = "seatflow.payments.failed.total";
    public static final String PAYMENTS_CONFLICTS = "seatflow.payments.conflicts.total";
    public static final String PAYMENTS_INTENT_DURATION = "seatflow.payments.intent.duration";

    // --- Ticket domain ---
    public static final String TICKETS_ISSUED = "seatflow.tickets.issued.total";

    // --- Outbox pipeline (all services) ---
    public static final String OUTBOX_PUBLISH_LATENCY = "seatflow.outbox.publish.latency";
    public static final String OUTBOX_PUBLISH_LATENCY_SECONDS = "seatflow.outbox.publish.latency.seconds";
    public static final String OUTBOX_RETRY_COUNT = "seatflow.outbox.retry.count.total";
    public static final String OUTBOX_DEAD_LETTER = "seatflow.outbox.dead.letter.total";
    public static final String OUTBOX_PENDING = "seatflow.outbox.pending";

    // --- Realtime ---
    public static final String WEBSOCKET_ACTIVE_CONNECTIONS = "seatflow.websocket.active.connections";

    // --- Common alias used in earlier spec examples ---
    public static final String OUTBOX_PUBLISH_LATENCY_ALIAS = OUTBOX_PUBLISH_LATENCY;
}
