package com.seatflow.reservation.scheduler;

import com.seatflow.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "reservation.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpirationScheduler {

    private final ReservationService reservationService;

    @Value("${reservation.cleanup.batch-size:100}")
    private int batchSize = 100;

    @Scheduled(fixedDelayString = "${reservation.cleanup.interval-ms:10000}")
    public void sweepExpiredReservations() {
        try {
            int expiredCount = reservationService.expireHoldReservations(Instant.now(), batchSize);
            if (expiredCount > 0) {
                log.info("Hold expiration sweeper completed batch. expiredReservationsCount={}", expiredCount);
            }
        } catch (Exception ex) {
            log.error("Unexpected error during reservation hold expiration sweep", ex);
        }
    }
}
