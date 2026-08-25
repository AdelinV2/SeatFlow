package com.seatflow.event.scheduler;

import com.seatflow.event.service.EventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "event.completion.enabled", havingValue = "true", matchIfMissing = true)
public class EventCompletionScheduler {

    private final EventService eventService;

    @Value("${event.completion.batch-size:50}")
    private int batchSize = 50;

    @Scheduled(cron = "${event.completion.cron:0 */15 * * * *}")
    public void sweepExpiredEvents() {
        try {
            int completed = eventService.completeExpiredEvents(Instant.now(), batchSize);
            if (completed > 0) {
                log.info("Auto-completed expired events. count={}", completed);
            }
        } catch (Exception ex) {
            log.error("Error during event completion sweep", ex);
        }
    }
}
