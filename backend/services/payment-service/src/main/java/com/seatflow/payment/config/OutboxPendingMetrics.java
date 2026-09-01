package com.seatflow.payment.config;

import com.seatflow.common.observability.metrics.SeatFlowMetricNames;
import com.seatflow.payment.repository.OutboxEventRepository;
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
                .tag("service", "payment-service")
                .description("Unpublished event rows waiting in the payment-service outbox")
                .register(meterRegistry);
    }
}
