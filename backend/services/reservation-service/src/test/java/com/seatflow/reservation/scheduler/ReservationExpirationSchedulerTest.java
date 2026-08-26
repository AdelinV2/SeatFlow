package com.seatflow.reservation.scheduler;

import com.seatflow.reservation.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationExpirationSchedulerTest {

    @Mock
    private ReservationService reservationService;

    @InjectMocks
    private ReservationExpirationScheduler scheduler;

    @Test
    void sweepInvokesServiceWithNowAndConfiguredBatchSize() {
        ReflectionTestUtils.setField(scheduler, "batchSize", 25);
        when(reservationService.expireHoldReservations(any(Instant.class), anyInt())).thenReturn(5);

        scheduler.sweepExpiredReservations();

        verify(reservationService).expireHoldReservations(any(Instant.class), eq(25));
    }

    @Test
    void sweepDoesNotPropagateServiceExceptions() {
        when(reservationService.expireHoldReservations(any(Instant.class), anyInt()))
                .thenThrow(new RuntimeException("boom"));

        scheduler.sweepExpiredReservations();

        verify(reservationService).expireHoldReservations(any(Instant.class), anyInt());
    }
}
