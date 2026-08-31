package com.seatflow.common.observability.config;

import com.seatflow.common.observability.metrics.MetricTagPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Micrometer metrics auto-configuration that applies to both Servlet and Reactive stacks.
 * Provides common tags (application, environment) and bounded URI tag filtering.
 */
@AutoConfiguration
public class SeatFlowMetricsAutoConfiguration {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> seatflowReactiveCommonTags(
            @Value("${spring.application.name:unknown}") String application,
            @Value("${SEATFLOW_DEPLOYMENT_ENV:local}") String environment) {
        return registry -> registry.config()
                .commonTags("application", application, "environment", environment);
    }

    @Bean
    public MeterFilter seatflowCommonUriTagFilter() {
        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(io.micrometer.core.instrument.Meter.Id id) {
                return MeterFilterReply.NEUTRAL;
            }

            @Override
            public io.micrometer.core.instrument.Meter.Id map(io.micrometer.core.instrument.Meter.Id id) {
                if ("http.server.requests".equals(id.getName())) {
                    String uri = id.getTag("uri");
                    if (uri != null) {
                        String sanitized = MetricTagPolicy.sanitizeUriTag(uri);
                        if (!sanitized.equals(uri)) {
                            return id.withTag(Tag.of("uri", sanitized));
                        }
                    }
                }
                return id;
            }
        };
    }
}
