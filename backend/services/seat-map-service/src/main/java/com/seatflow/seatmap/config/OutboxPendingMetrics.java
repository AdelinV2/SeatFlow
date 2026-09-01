package com.seatflow.seatmap.config;

import com.seatflow.common.observability.metrics.SeatFlowMetricNames;
import com.seatflow.seatmap.repository.OutboxEventRepository;
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
                .tag("service", "seat-map-service")
                .description("Unpublished event rows waiting in the seat-map-service outbox")
                .register(meterRegistry);
    }
}
