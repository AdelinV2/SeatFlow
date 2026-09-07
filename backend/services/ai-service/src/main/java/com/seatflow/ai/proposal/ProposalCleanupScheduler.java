package com.seatflow.ai.proposal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded scheduled cleanup for expired proposals (TASK-P15-005 section 5).
 *
 * <p>Removes at most 100 expired {@code ACTIVE} entries per run so cleanup stays bounded and
 * thread-safe. A proposal TTL expiring never affects real Reservation Service holds, which live
 * exclusively in Reservation Service with their own 15-minute semantics.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProposalCleanupScheduler {

    private final ProposalStore proposals;

    @Scheduled(fixedDelayString = "${seatflow.ai.proposal.cleanup-interval:PT1M}")
    public void cleanup() {
        try {
            int marked = proposals.cleanupExpiredBounded(100);
            if (marked > 0) {
                log.info("AI proposal scheduled cleanup marked {} proposals expired", marked);
            }
        } catch (Exception ex) {
            log.warn("AI proposal scheduled cleanup failed without affecting booking health", ex);
        }
    }
}
