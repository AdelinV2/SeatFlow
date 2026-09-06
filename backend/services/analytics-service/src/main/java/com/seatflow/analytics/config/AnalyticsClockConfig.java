package com.seatflow.analytics.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Injected UTC clock for deterministic analytics date-range defaults and freshness metadata.
 *
 * <p>TASK-P14-004: default ranges ({@code [utcToday - 29d, utcToday]}) and
 * {@code generatedAt} must not call {@code LocalDate.now()} directly so tests can fix time.
 */
@Configuration
public class AnalyticsClockConfig {

    @Bean
    public Clock analyticsClock() {
        return Clock.systemUTC();
    }
}
