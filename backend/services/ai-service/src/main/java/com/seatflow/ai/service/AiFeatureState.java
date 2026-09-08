package com.seatflow.ai.service;

/**
 * Stable AI feature state (TASK-P15-001).
 *
 * <p>Process health ({@code /actuator/health}) is independent from provider availability; a Groq
 * outage must never remove the container from discovery. Distinguish process health from these
 * feature states.
 */
public enum AiFeatureState {
    DISABLED,
    READY,
    MISCONFIGURED,
    RATE_LIMITED,
    PROVIDER_UNAVAILABLE
}
