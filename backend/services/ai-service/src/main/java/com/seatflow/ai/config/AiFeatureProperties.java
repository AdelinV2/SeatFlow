package com.seatflow.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SeatFlow-owned AI feature flag (TASK-P15-001).
 *
 * <p>Bound from {@code seatflow.ai.enabled=${AI_ENABLED:false}}. The flag alone controls whether
 * AI calls are attempted; provider credentials come only from {@code GROQ_*} environment variables
 * mapped through Spring AI 2.0.1 properties. AI disabled must allow full startup without a key.
 */
@ConfigurationProperties(prefix = "seatflow.ai")
public record AiFeatureProperties(boolean enabled) {
}
