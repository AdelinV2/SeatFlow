package com.seatflow.event.web.controller;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.observability.handler.GlobalExceptionHandler;
import com.seatflow.event.config.SecurityConfig;
import com.seatflow.event.model.enums.EventSessionStatus;
import com.seatflow.event.service.EventSessionService;
import com.seatflow.event.web.dto.response.EventSessionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminEventSessionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AdminEventSessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventSessionService eventSessionService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private com.seatflow.common.security.converter.JwtRoleConverter jwtRoleConverter;

    private static EventSessionResponse sampleResponse(UUID sessionId, UUID eventId) {
        return new EventSessionResponse(sessionId, eventId,
                Instant.parse("2027-05-01T19:30:00Z"), Instant.parse("2027-05-01T21:30:00Z"),
                null, null, EventSessionStatus.SCHEDULED, null, Instant.now(), Instant.now());
    }

    private static String validCreateBody() {
        return "{\"startsAt\":\"2027-05-01T19:30:00Z\",\"endsAt\":\"2027-05-01T21:30:00Z\"}";
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createSession_admin_returns201() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(eventSessionService.createSession(any(), any())).thenReturn(sampleResponse(sessionId, eventId));

        mockMvc.perform(post("/api/admin/events/{eventId}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON).content(validCreateBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(sessionId.toString()))
                .andExpect(jsonPath("$.eventId").value(eventId.toString()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listSessions_admin_returns200() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(eventSessionService.listSessionsForAdmin(eventId))
                .thenReturn(List.of(sampleResponse(UUID.randomUUID(), eventId)));

        mockMvc.perform(get("/api/admin/events/{eventId}/sessions", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateSession_admin_returns200() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(eventSessionService.updateSession(any(), any(), any()))
                .thenReturn(sampleResponse(sessionId, eventId));

        mockMvc.perform(put("/api/admin/events/{eventId}/sessions/{sessionId}", eventId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON).content(validCreateBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sessionId.toString()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteSession_admin_returns204() throws Exception {
        mockMvc.perform(delete("/api/admin/events/{eventId}/sessions/{sessionId}",
                        UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void createSession_customer_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/events/{eventId}/sessions", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(validCreateBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void listSessions_customer_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/events/{eventId}/sessions", UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void updateSession_customer_returns403() throws Exception {
        mockMvc.perform(put("/api/admin/events/{eventId}/sessions/{sessionId}",
                        UUID.randomUUID(), UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(validCreateBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void deleteSession_customer_returns403() throws Exception {
        mockMvc.perform(delete("/api/admin/events/{eventId}/sessions/{sessionId}",
                        UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    void createSession_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/admin/events/{eventId}/sessions", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(validCreateBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createSession_invalidBody_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/events/{eventId}/sessions", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateSession_mismatchedPair_returns404() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(eventSessionService.updateSession(any(), any(), any()))
                .thenThrow(new ResourceNotFoundException("EventSession", sessionId));

        mockMvc.perform(put("/api/admin/events/{eventId}/sessions/{sessionId}",
                        UUID.randomUUID(), sessionId)
                        .contentType(MediaType.APPLICATION_JSON).content(validCreateBody()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateSession_lockedSession_returns409() throws Exception {
        when(eventSessionService.updateSession(any(), any(), any()))
                .thenThrow(new ConflictException("Session schedule is locked", ErrorCode.CONFLICT));

        mockMvc.perform(put("/api/admin/events/{eventId}/sessions/{sessionId}",
                        UUID.randomUUID(), UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(validCreateBody()))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteSession_lockedSession_returns409() throws Exception {
        org.mockito.Mockito.doThrow(new ConflictException("Session schedule is locked", ErrorCode.CONFLICT))
                .when(eventSessionService).deleteSession(any(), any());

        mockMvc.perform(delete("/api/admin/events/{eventId}/sessions/{sessionId}",
                        UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createSession_invalidSchedule_returns400() throws Exception {
        when(eventSessionService.createSession(any(), any()))
                .thenThrow(new ValidationException("Session start time must be before end time",
                        ErrorCode.INVALID_REQUEST));

        mockMvc.perform(post("/api/admin/events/{eventId}/sessions", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(validCreateBody()))
                .andExpect(status().isBadRequest());
    }
}
