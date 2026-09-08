package com.seatflow.ai.api;

import com.seatflow.ai.config.SecurityConfig;
import com.seatflow.ai.context.AiRequestContextFactory;
import com.seatflow.ai.orchestration.AssistantOrchestrator;
import com.seatflow.ai.orchestration.AssistantState;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.observability.handler.GlobalExceptionHandler;
import com.seatflow.common.security.converter.JwtRoleConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller/security tests for chat + reset (TASK-P15-004 sections 3, 12).
 */
@WebMvcTest({AssistantChatController.class, ConversationController.class})
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AssistantChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssistantOrchestrator orchestrator;

    @MockitoBean
    private AiRequestContextFactory requestContexts;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private JwtRoleConverter jwtRoleConverter;

    @Test
    @DisplayName("unauthenticated chat returns 401")
    void unauthenticatedChat401() throws Exception {
        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("authenticated chat returns typed contract without secrets")
    void authenticatedChatReturnsContract() throws Exception {
        UUID conversationId = UUID.randomUUID();
        when(requestContexts.requireAuthenticated()).thenReturn(
                new com.seatflow.ai.context.AiRequestContext("bearer", "corr", "owner-1"));
        when(orchestrator.chat(any(), anyString(), anyString(), any())).thenReturn(
                new com.seatflow.ai.api.dto.AssistantChatResponse(conversationId, "Hello",
                        AssistantState.IDLE, List.of(), List.of(), null));

        mockMvc.perform(post("/api/ai/chat").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId.toString()))
                .andExpect(jsonPath("$.state").value("IDLE"))
                .andExpect(jsonPath("$.assistantMessage").value("Hello"))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(jsonPath("$.groqApiKey").doesNotExist())
                .andExpect(jsonPath("$.authorization").doesNotExist())
                .andExpect(jsonPath("$.reasoning").doesNotExist())
                .andExpect(jsonPath("$.chainOfThought").doesNotExist());
    }

    @Test
    @DisplayName("cross-owner/unknown conversation maps to 404")
    void unknownConversation404() throws Exception {
        when(requestContexts.requireAuthenticated()).thenReturn(
                new com.seatflow.ai.context.AiRequestContext("bearer", "corr", "owner-1"));
        when(orchestrator.chat(any(), anyString(), anyString(), any()))
                .thenThrow(new ResourceNotFoundException("Conversation not found"));

        mockMvc.perform(post("/api/ai/chat").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + UUID.randomUUID() + "\",\"message\":\"hi\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE reset returns 204 for owner; 404 otherwise")
    void resetContract() throws Exception {
        when(requestContexts.requireAuthenticated()).thenReturn(
                new com.seatflow.ai.context.AiRequestContext("bearer", "corr", "owner-1"));
        UUID owned = UUID.randomUUID();
        when(orchestrator.reset(owned, "owner-1")).thenReturn(true);

        mockMvc.perform(delete("/api/ai/conversations/" + owned).with(jwt()))
                .andExpect(status().isNoContent());

        UUID unknown = UUID.randomUUID();
        when(orchestrator.reset(unknown, "owner-1")).thenReturn(false);
        mockMvc.perform(delete("/api/ai/conversations/" + unknown).with(jwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("unauthenticated reset returns 401")
    void unauthenticatedReset401() throws Exception {
        mockMvc.perform(delete("/api/ai/conversations/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
