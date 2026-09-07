package com.seatflow.ai.api;

import com.seatflow.ai.config.SecurityConfig;
import com.seatflow.ai.service.AiFeatureState;
import com.seatflow.ai.service.AiStatusService;
import com.seatflow.common.observability.handler.GlobalExceptionHandler;
import com.seatflow.common.security.converter.JwtRoleConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AiStatusController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AiStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiStatusService statusService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private JwtRoleConverter jwtRoleConverter;

    @Test
    @DisplayName("Unauthenticated access to AI status returns 401")
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/ai/status")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Authenticated USER can reach status and response leaks no secrets")
    void userCanReachStatusWithoutSecretLeak() throws Exception {
        when(statusService.isEnabled()).thenReturn(true);
        when(statusService.currentState()).thenReturn(AiFeatureState.READY);
        when(statusService.configuredModel()).thenReturn("openai/gpt-oss-20b");

        mockMvc.perform(get("/api/ai/status").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.state").value("READY"))
                .andExpect(jsonPath("$.model").value("openai/gpt-oss-20b"))
                // No secret-bearing fields may ever appear in the status DTO.
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(jsonPath("$.groqApiKey").doesNotExist())
                .andExpect(jsonPath("$.authorization").doesNotExist());
    }

    @Test
    @DisplayName("ADMIN receives the same customer status shape (no additional tools)")
    void adminReceivesSameStatusShape() throws Exception {
        when(statusService.isEnabled()).thenReturn(false);
        when(statusService.currentState()).thenReturn(AiFeatureState.DISABLED);
        when(statusService.configuredModel()).thenReturn("openai/gpt-oss-20b");

        mockMvc.perform(get("/api/ai/status").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.state").value("DISABLED"));
    }
}
