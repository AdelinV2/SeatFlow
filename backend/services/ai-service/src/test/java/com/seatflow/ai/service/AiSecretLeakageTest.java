package com.seatflow.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.ai.api.dto.AiFeatureStatusResponse;
import com.seatflow.common.observability.logging.SensitiveDataMaskingConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiSecretLeakageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Status DTO serialization never contains key material")
    void statusDtoNeverContainsKey() throws Exception {
        var response = new AiFeatureStatusResponse(true, AiFeatureState.READY, "openai/gpt-oss-20b");
        String json = mapper.writeValueAsString(response);

        assertThat(json).doesNotContain("gsk_");
        assertThat(json).doesNotContain("GROQ_API_KEY");
        assertThat(json).doesNotContain("Authorization");
        assertThat(json).contains("openai/gpt-oss-20b");
    }

    @Test
    @DisplayName("Shared log masking redacts Authorization bearer values")
    void logMaskingRedactsAuthorization() {
        String input = "GET /api/ai/status Authorization: Bearer eyJhbGciOiJFUzI1NiJ9.payload.signature";
        String masked = SensitiveDataMaskingConverter.mask(input);

        assertThat(masked).doesNotContain("eyJhbGciOiJFUzI1NiJ9");
        assertThat(masked).contains("Bearer [MASKED_JWT]");
    }

    @Test
    @DisplayName("Shared log masking redacts api_key style key-value secrets")
    void logMaskingRedactsApiKeyValues() {
        String input = "provider call failed api_key=gsk_secret_xyz_123";
        String masked = SensitiveDataMaskingConverter.mask(input);

        assertThat(masked).doesNotContain("gsk_secret_xyz_123");
        assertThat(masked).contains("[MASKED]");
    }
}
