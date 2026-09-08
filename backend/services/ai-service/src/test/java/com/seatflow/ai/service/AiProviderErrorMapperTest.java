package com.seatflow.ai.service;

import com.seatflow.ai.exception.AiProviderUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiProviderErrorMapperTest {

    private final AiProviderErrorMapper mapper = new AiProviderErrorMapper();

    @Test
    @DisplayName("401 maps to MISCONFIGURED and never retries")
    void unauthorizedMapsToMisconfigured() {
        assertThat(mapper.categorizeHttpStatus(401)).isEqualTo(AiFeatureState.MISCONFIGURED);
        assertThat(mapper.isRetryableHttpStatus(401)).isFalse();

        AiProviderUnavailableException ex = mapper.toException(401);
        assertThat(ex.getHttpStatus()).isEqualTo(503);
    }

    @Test
    @DisplayName("429 maps to RATE_LIMITED with 429 and never retries")
    void rateLimitedFailsFast() {
        assertThat(mapper.categorizeHttpStatus(429)).isEqualTo(AiFeatureState.RATE_LIMITED);
        assertThat(mapper.isRetryableHttpStatus(429)).isFalse();

        AiProviderUnavailableException ex = mapper.toException(429);
        assertThat(ex.getHttpStatus()).isEqualTo(429);
        assertThat(ex.getMessage()).contains("rate limit");
    }

    @Test
    @DisplayName("5xx maps to PROVIDER_UNAVAILABLE and is the only retryable category")
    void serverErrorIsRetryableOnce() {
        assertThat(mapper.categorizeHttpStatus(500)).isEqualTo(AiFeatureState.PROVIDER_UNAVAILABLE);
        assertThat(mapper.categorizeHttpStatus(503)).isEqualTo(AiFeatureState.PROVIDER_UNAVAILABLE);
        assertThat(mapper.isRetryableHttpStatus(500)).isTrue();
        assertThat(mapper.isRetryableHttpStatus(503)).isTrue();
    }

    @Test
    @DisplayName("400/403/404 never retry")
    void clientErrorsNeverRetry() {
        assertThat(mapper.isRetryableHttpStatus(400)).isFalse();
        assertThat(mapper.isRetryableHttpStatus(403)).isFalse();
        assertThat(mapper.isRetryableHttpStatus(404)).isFalse();
    }

    @Test
    @DisplayName("Mapped exception never leaks raw provider body or key material")
    void exceptionNeverLeaksRawBody() {
        String rawBody = "{\"error\":{\"message\":\"invalid api key gsk_secret_xyz\"},\"key\":\"gsk_secret_xyz\"}";
        AiProviderUnavailableException ex401 = mapper.toException(401);
        AiProviderUnavailableException ex429 = mapper.toException(429);
        AiProviderUnavailableException ex500 = mapper.toException(500);

        assertThat(ex401.getMessage()).doesNotContain("gsk_secret_xyz").doesNotContain(rawBody);
        assertThat(ex429.getMessage()).doesNotContain("gsk_secret_xyz").doesNotContain(rawBody);
        assertThat(ex500.getMessage()).doesNotContain("gsk_secret_xyz").doesNotContain(rawBody);
    }
}
