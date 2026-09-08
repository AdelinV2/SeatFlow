package com.seatflow.ai.service;

import com.seatflow.ai.exception.AiProviderUnavailableException;
import org.springframework.stereotype.Component;

/**
 * Maps mocked/live provider HTTP failures to stable internal categories without leaking raw bodies.
 *
 * <p>Retry policy enforced by callers (TASK-P15-001 section 8.1; no automatic retry:
 * {@code spring.ai.openai.max-retries=0} in {@code application.yaml}):
 * <ul>
 *   <li>{@code 400/401/403/404} → no retry;</li>
 *   <li>{@code 429} → fail fast to {@code RATE_LIMITED} (HTTP 429, no retry storm);</li>
 *   <li>transient network/5xx/timeout → no automatic retry; callers fail fast to
 *       {@code PROVIDER_UNAVAILABLE} (HTTP 503).</li>
 * </ul>
 * Do not assert exact provider free-tier limits in code.
 */
@Component
public class AiProviderErrorMapper {

    public AiFeatureState categorizeHttpStatus(int httpStatus) {
        if (httpStatus == 429) {
            return AiFeatureState.RATE_LIMITED;
        }
        if (httpStatus == 401 || httpStatus == 403) {
            return AiFeatureState.MISCONFIGURED;
        }
        if (httpStatus >= 500) {
            return AiFeatureState.PROVIDER_UNAVAILABLE;
        }
        return AiFeatureState.PROVIDER_UNAVAILABLE;
    }

    public boolean isRetryableHttpStatus(int httpStatus) {
        return httpStatus >= 500;
    }

    /**
     * Creates a safe exception for the given provider HTTP status. The raw body is accepted only so
     * callers can log a redacted diagnostic server-side; it is never included in the message.
     */
    public AiProviderUnavailableException toException(int httpStatus) {
        return switch (categorizeHttpStatus(httpStatus)) {
            case RATE_LIMITED -> new AiProviderUnavailableException(
                    "AI provider rate limit reached. Please try again shortly.", 429);
            case MISCONFIGURED -> new AiProviderUnavailableException(
                    "AI provider credentials are invalid. AI is temporarily unavailable.", 503);
            default -> new AiProviderUnavailableException(
                    "AI provider is temporarily unavailable. Core booking remains available.", 503);
        };
    }
}
