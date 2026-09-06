package com.seatflow.event.web.controller;

import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.observability.handler.GlobalExceptionHandler;
import com.seatflow.event.config.SecurityConfig;
import com.seatflow.event.service.EventSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P12-008 scenario H: session authorization boundary regression.
 *
 * <p>Enforced contract under test (the whole boundary, nothing more):
 * <ul>
 *   <li>role boundary — every {@code /api/admin/events/{eventId}/sessions}
 *       route requires {@code ROLE_ADMIN}: unauthenticated callers receive
 *       401 and customers receive 403 on all four endpoints;</li>
 *   <li>scope boundary — update/delete/list resolve the session through the
 *       event-scoped {@code findByIdAndEvent_Id} lookup, so an event/session
 *       path mismatch is answered with 404, the same status as a missing
 *       object, and nothing is mutated. The repository-level proof that a
 *       foreign pair resolves empty lives in
 *       {@code EventSessionRepositoryTest#shouldFindSessionOnlyForMatchingEventPair};</li>
 *   <li>no per-organizer tenancy exists: {@code Event} carries no organizer
 *       identity (V1 DDL and entity), the role model defines only
 *       CUSTOMER/STAFF/ADMIN ({@code SecurityRoles}), and every admin route is
 *       {@code hasRole('ADMIN')} platform-global (ADR-005). Per-organizer
 *       isolation therefore has no enforceable boundary to test here; the
 *       contradictory P12-002/P12-008 organizer-ownership criteria need a
 *       future ADR plus schema change (see review ledger REV-001).</li>
 * </ul>
 */
@WebMvcTest(controllers = AdminEventSessionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class EventSessionAuthorizationRegressionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventSessionService eventSessionService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private com.seatflow.common.security.converter.JwtRoleConverter jwtRoleConverter;

    private static String validBody() {
        return "{\"startsAt\":\"2027-05-01T19:30:00Z\",\"endsAt\":\"2027-05-01T21:30:00Z\"}";
    }

    @Test
    void createSession_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/admin/events/{eventId}/sessions", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listSessions_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/events/{eventId}/sessions", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateSession_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/api/admin/events/{eventId}/sessions/{sessionId}",
                        UUID.randomUUID(), UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteSession_unauthenticated_returns401() throws Exception {
        mockMvc.perform(delete("/api/admin/events/{eventId}/sessions/{sessionId}",
                        UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void createSession_customer_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/events/{eventId}/sessions", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
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
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
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
    @WithMockUser(roles = "ADMIN")
    void createSession_unknownEvent_returns404() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(eventSessionService.createSession(any(), any()))
                .thenThrow(new ResourceNotFoundException("Event", eventId));

        mockMvc.perform(post("/api/admin/events/{eventId}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listSessions_unknownEvent_returns404() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(eventSessionService.listSessionsForAdmin(eq(eventId)))
                .thenThrow(new ResourceNotFoundException("Event", eventId));

        mockMvc.perform(get("/api/admin/events/{eventId}/sessions", eventId))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateSession_unknownEvent_returns404() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(eventSessionService.updateSession(eq(eventId), eq(sessionId), any()))
                .thenThrow(new ResourceNotFoundException("Event", eventId));

        mockMvc.perform(put("/api/admin/events/{eventId}/sessions/{sessionId}", eventId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateSession_mismatchedPair_returns404WithScopedLookup() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID foreignSessionId = UUID.randomUUID();
        when(eventSessionService.updateSession(eq(eventId), eq(foreignSessionId), any()))
                .thenThrow(new ResourceNotFoundException("EventSession", foreignSessionId));

        mockMvc.perform(put("/api/admin/events/{eventId}/sessions/{sessionId}", eventId, foreignSessionId)
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isNotFound());

        // The service receives the exact mismatched pair, so the scoped
        // findByIdAndEvent_Id lookup — not a session-only lookup — decides.
        verify(eventSessionService).updateSession(eq(eventId), eq(foreignSessionId), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteSession_mismatchedPair_returns404WithoutMutation() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID foreignSessionId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("EventSession", foreignSessionId))
                .when(eventSessionService).deleteSession(eq(eventId), eq(foreignSessionId));

        mockMvc.perform(delete("/api/admin/events/{eventId}/sessions/{sessionId}", eventId, foreignSessionId))
                .andExpect(status().isNotFound());

        verify(eventSessionService).deleteSession(eq(eventId), eq(foreignSessionId));
    }
}
