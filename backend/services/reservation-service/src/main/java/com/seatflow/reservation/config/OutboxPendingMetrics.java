package com.seatflow.reservation.config;

import com.seatflow.common.observability.metrics.SeatFlowMetricNames;
import com.seatflow.reservation.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxPendingMetrics {

    private final OutboxEventRepository outboxEventRepository;
    private final MeterRegistry meterRegistry;

    @PostConstruct
    void registerOutboxPendingGauge() {
        Gauge.builder(SeatFlowMetricNames.OUTBOX_PENDING,
                        outboxEventRepository,
                        OutboxEventRepository::countByPublishedAtIsNull)
                .tag("service", "reservation-service")
                .description("Unpublished event rows waiting in the reservation-service outbox")
                .register(meterRegistry);
    }
}
