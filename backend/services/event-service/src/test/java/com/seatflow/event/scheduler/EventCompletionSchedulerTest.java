package com.seatflow.event.scheduler;

import com.seatflow.event.service.EventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventCompletionSchedulerTest {

    @Mock
    private EventService eventService;

    @InjectMocks
    private EventCompletionScheduler scheduler;

    @Test
    void sweep_completesExpiredEventsWithConfiguredBatchSize() {
        scheduler.sweepExpiredEvents();

        verify(eventService).completeExpiredEvents(any(Instant.class), eq(50));
    }

    @Test
    void sweep_logsAndDoesNotPropagateExceptions() {
        when(eventService.completeExpiredEvents(any(Instant.class), anyInt()))
                .thenThrow(new RuntimeException("broker down"));

        scheduler.sweepExpiredEvents();

        verify(eventService).completeExpiredEvents(any(Instant.class), eq(50));
    }
}
