package com.seatflow.event.web.controller;

import com.seatflow.common.observability.handler.GlobalExceptionHandler;
import com.seatflow.event.config.SecurityConfig;
import com.seatflow.event.service.EventPricingService;
import com.seatflow.event.service.EventService;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P12-008 scenario J (event HTTP leg): pre-P12 schedule writes carrying a
 * legacy event-level instant ({@code eventDate}, {@code event_date},
 * {@code startsAt}) are rejected with 400 at the HTTP boundary — never
 * silently ignored, and never forwarded to the service where they could
 * resurrect event-level schedule ownership.
 *
 * <p>Record-shape proof lives in {@code LegacyScheduleRemovalContractTest};
 * this slice proves the running Spring stack actually enforces
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} end to end.
 */
@WebMvcTest(controllers = AdminEventController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class LegacyScheduleWriteHttpRejectionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventService eventService;

    @MockitoBean
    private EventPricingService eventPricingService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private com.seatflow.common.security.converter.JwtRoleConverter jwtRoleConverter;

    @Test
    @DisplayName("Create with legacy eventDate is rejected with 400 and never reaches the service")
    @WithMockUser(roles = "ADMIN")
    void createWithLegacyEventDateIsRejected() throws Exception {
        String legacyCreate = """
                {"venueId":"%s","title":"Hamlet","description":"A play",
                 "category":"OTHER","eventDate":"2027-05-01T19:30:00Z"}
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/admin/events")
                        .contentType(MediaType.APPLICATION_JSON).content(legacyCreate))
                .andExpect(status().isBadRequest());

        verify(eventService, never()).createEvent(any());
    }

    @Test
    @DisplayName("Update with legacy schedule fields is rejected with 400 and never reaches the service")
    @WithMockUser(roles = "ADMIN")
    void updateWithLegacyScheduleFieldsIsRejected() throws Exception {
        String legacyUpdate = """
                {"title":"Hamlet","eventDate":"2027-06-01T19:30:00Z",
                 "startsAt":"2027-06-01T19:30:00Z","event_date":"2027-06-01T19:30:00Z"}
                """;

        mockMvc.perform(put("/api/admin/events/{eventId}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(legacyUpdate))
                .andExpect(status().isBadRequest());

        verify(eventService, never()).updateEvent(any(), any());
    }
}
