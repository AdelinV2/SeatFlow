package com.seatflow.ai.config;

import com.seatflow.ai.service.AiFeatureState;
import com.seatflow.ai.service.AiStatusService;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-P15-001 mandatory startup tests: disabled without key starts; enabled without key starts as
 * MISCONFIGURED with chat unavailable; no datasource auto-configuration is present.
 */
@SpringBootTest(properties = {
        "seatflow.ai.enabled=false",
        "GROQ_API_KEY=",
        "GROQ_MODEL=openai/gpt-oss-20b"
})
@ActiveProfiles("test")
class AiDisabledStartupTest {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private AiStatusService statusService;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("AI_ENABLED=false with no key starts as DISABLED and chat is unavailable")
    void disabledWithoutKeyStarts() {
        assertThat(statusService.currentState()).isEqualTo(AiFeatureState.DISABLED);
        assertThat(statusService.isChatAvailable()).isFalse();
    }

    @Test
    @DisplayName("ai-service has no datasource auto-configuration or domain DB dependency")
    void noDatasourcePresent() {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
    }
}
