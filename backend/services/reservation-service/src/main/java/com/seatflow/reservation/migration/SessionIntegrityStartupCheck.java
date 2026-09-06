package com.seatflow.reservation.migration;

import com.seatflow.reservation.repository.ReservationRepository;
import com.seatflow.reservation.repository.SeatHoldRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * P12-007 (REV-004, option b): post-deploy data-quality alert for NULL
 * {@code event_session_id} rows.
 *
 * <p>V8 is a fail-closed migrate-time gate, not a {@code NOT NULL} constraint:
 * the hard constraint is deferred to tracked follow-up {@code TASK-P12-009}
 * because the backfill verification suites
 * ({@code SessionScopedInventoryIntegrationTest} orders 11-13) intentionally
 * persist legacy-NULL rows through JPA on the production migration chain to
 * verify {@link SessionInventoryBackfillService} itself. Adding the constraint
 * now would break those inserts; moving them to a staged pre-constraint schema
 * requires re-wiring Spring Data repositories against a second DataSource and
 * would split the single-database concurrency oracle, so the deferral is kept
 * and monitored instead.
 *
 * <p>This check is deliberately fail-open: it alerts (ERROR log) when orphans
 * exist but never blocks startup, because the backfill deployment step may
 * legitimately run after the service boots. Enforcement at migrate time stays
 * with V8, which aborts before any schema change while NULLs remain.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionIntegrityStartupCheck {

    private final ReservationRepository reservationRepository;
    private final SeatHoldRepository seatHoldRepository;

    public record SessionIntegrityReport(long nullReservations, long nullSeatHolds) {
        public boolean clean() {
            return nullReservations == 0 && nullSeatHolds == 0;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Optional<SessionIntegrityReport> report = check();
        if (report.isEmpty()) {
            log.warn("Session integrity check unavailable at startup; V8 migrate-time gate remains the enforcement point");
            return;
        }
        SessionIntegrityReport result = report.get();
        if (!result.clean()) {
            log.error("P12-007 data-quality ALERT: {} reservation(s) and {} seat hold(s) still lack event_session_id. "
                            + "Run SessionInventoryBackfillService before relying on session-scoped inventory; "
                             + "hard NOT NULL is tracked as TASK-P12-009",
                    result.nullReservations(), result.nullSeatHolds());
        } else {
            log.info("Session integrity check passed: zero NULL event_session_id rows");
        }
    }

    Optional<SessionIntegrityReport> check() {
        try {
            return Optional.of(new SessionIntegrityReport(
                    reservationRepository.countReservationsWithNullSession(),
                    seatHoldRepository.countSeatHoldsWithNullSession()));
        } catch (Exception ex) {
            log.warn("Session integrity check skipped: repository query failed", ex);
            return Optional.empty();
        }
    }
}
