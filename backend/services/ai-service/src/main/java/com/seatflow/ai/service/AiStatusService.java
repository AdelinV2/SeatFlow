package com.seatflow.ai.service;

import com.seatflow.ai.config.AiFeatureProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Determines safe AI feature availability without any provider network call.
 *
 * <p>Rules (TASK-P15-001 section 8):
 * <ul>
 *   <li>{@code AI_ENABLED=false} → {@code DISABLED} (key presence irrelevant);</li>
 *   <li>{@code AI_ENABLED=true} + blank key → {@code MISCONFIGURED};</li>
 *   <li>{@code AI_ENABLED=true} + key present → {@code READY} (liveness, not a reachability probe).</li>
 * </ul>
 * {@code RATE_LIMITED} and {@code PROVIDER_UNAVAILABLE} are observed at chat-call time via
 * {@link AiProviderErrorMapper}, never by blocking startup or readiness on Groq reachability.
 */
@Service
@RequiredArgsConstructor
public class AiStatusService {

    private final AiFeatureProperties featureProperties;

    @Value("${GROQ_API_KEY:}")
    private String groqApiKey;

    @Value("${GROQ_MODEL:openai/gpt-oss-20b}")
    private String groqModel;

    public AiFeatureState currentState() {
        if (!featureProperties.enabled()) {
            return AiFeatureState.DISABLED;
        }
        if (groqApiKey == null || groqApiKey.isBlank()) {
            return AiFeatureState.MISCONFIGURED;
        }
        return AiFeatureState.READY;
    }

    public boolean isEnabled() {
        return featureProperties.enabled();
    }

    public String configuredModel() {
        return groqModel;
    }

    /** Chat-capable path is available only when the feature is enabled and configured. */
    public boolean isChatAvailable() {
        return currentState() == AiFeatureState.READY;
    }
}
