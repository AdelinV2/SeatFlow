package com.seatflow.event.web.controller;

import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.observability.handler.GlobalExceptionHandler;
import com.seatflow.event.config.SecurityConfig;
import com.seatflow.event.model.enums.EventSessionStatus;
import com.seatflow.event.model.enums.EventStatus;
import com.seatflow.event.service.EventSessionService;
import com.seatflow.event.web.dto.response.SessionBookingContextResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalEventSessionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class InternalEventSessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventSessionService eventSessionService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private com.seatflow.common.security.converter.JwtRoleConverter jwtRoleConverter;

    @Test
    @WithMockUser
    void getBookingContext_authenticated_returns200WithServerDerivedEventId() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID venueId = UUID.randomUUID();
        when(eventSessionService.getBookingContext(sessionId)).thenReturn(
                new SessionBookingContextResponse(sessionId, eventId, EventStatus.PUBLISHED,
                        EventSessionStatus.SCHEDULED,
                        Instant.parse("2027-05-01T19:30:00Z"), Instant.parse("2027-05-01T21:30:00Z"),
                        null, null, venueId));

        mockMvc.perform(get("/internal/event-sessions/{sessionId}/booking-context", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventSessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.venueId").value(venueId.toString()));
    }

    @Test
    void getBookingContext_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/internal/event-sessions/{sessionId}/booking-context", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void getBookingContext_unknownSession_returns404() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(eventSessionService.getBookingContext(any()))
                .thenThrow(new ResourceNotFoundException("EventSession", sessionId));

        mockMvc.perform(get("/internal/event-sessions/{sessionId}/booking-context", sessionId))
                .andExpect(status().isNotFound());
    }
}
