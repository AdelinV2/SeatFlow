package com.seatflow.ai.config;

import com.seatflow.ai.service.AiFeatureState;
import com.seatflow.ai.service.AiStatusService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "seatflow.ai.enabled=true",
        "GROQ_API_KEY=",
        "GROQ_MODEL=openai/gpt-oss-20b"
})
@ActiveProfiles("test")
class AiMisconfiguredStartupTest {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private AiStatusService statusService;

    @Test
    @DisplayName("AI_ENABLED=true with blank key starts as MISCONFIGURED with chat unavailable")
    void enabledWithoutKeyIsMisconfigured() {
        assertThat(statusService.currentState()).isEqualTo(AiFeatureState.MISCONFIGURED);
        assertThat(statusService.isChatAvailable()).isFalse();
    }
}
