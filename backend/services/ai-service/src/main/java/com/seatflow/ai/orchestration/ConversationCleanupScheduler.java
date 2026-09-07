package com.seatflow.ai.orchestration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded scheduled cleanup for expired conversations (TASK-P15-004 section 4).
 *
 * <p>Runs with a fixed delay and removes at most 100 expired entries per run so cleanup stays
 * bounded and thread-safe. Chat memory for removed conversations is cleared opportunistically by
 * the orchestrator on access; this sweep handles idle conversations that receive no further turns.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationCleanupScheduler {

    private final ConversationStore conversations;

    @Scheduled(fixedDelayString = "${seatflow.ai.conversation.cleanup-interval:PT5M}")
    public void cleanup() {
        try {
            int removed = conversations.cleanupExpiredBounded(100);
            if (removed > 0) {
                log.info("AI conversation scheduled cleanup removed {} expired conversations", removed);
            }
        } catch (Exception ex) {
            log.warn("AI conversation scheduled cleanup failed without affecting booking health", ex);
        }
    }
}
