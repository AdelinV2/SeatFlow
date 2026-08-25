package com.seatflow.event.web.controller;

import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.observability.handler.GlobalExceptionHandler;
import com.seatflow.event.client.SeatMapClientUnavailableException;
import com.seatflow.event.config.SecurityConfig;
import com.seatflow.event.model.enums.EventCategory;
import com.seatflow.event.model.enums.EventStatus;
import com.seatflow.event.service.EventService;
import com.seatflow.event.web.dto.response.EventDetailResponse;
import com.seatflow.event.web.dto.response.EventSeatMapResponse;
import com.seatflow.event.web.dto.response.EventSummaryResponse;
import com.seatflow.event.web.dto.response.PricingTierResponse;
import com.seatflow.event.web.dto.response.SeatMapSectionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EventController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventService eventService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private com.seatflow.common.security.converter.JwtRoleConverter jwtRoleConverter;

    @Test
    void listEvents_unauthenticated_returns200() throws Exception {
        PagedResult<EventSummaryResponse> page = PagedResult.of(List.of(), 0, 20, 0);
        when(eventService.findPublishedEvents(any(), any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void getEvent_unauthenticated_returns200() throws Exception {
        UUID eventId = UUID.randomUUID();
        EventDetailResponse response = new EventDetailResponse(eventId, UUID.randomUUID(), "Hamlet", "desc",
                EventCategory.OTHER, null, Instant.now(), EventStatus.PUBLISHED, List.of(), Instant.now(), Instant.now());
        when(eventService.getPublishedEvent(eventId)).thenReturn(response);

        mockMvc.perform(get("/api/events/{id}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(eventId.toString()));
    }

    @Test
    void getEvent_notFound_returns404() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(eventService.getPublishedEvent(eventId)).thenThrow(new ResourceNotFoundException("Event", eventId));

        mockMvc.perform(get("/api/events/{id}", eventId))
                .andExpect(status().isNotFound());
    }

    @Test
    void getSeatMap_returns200_withEventSeatMapResponse() throws Exception {
        UUID eventId = UUID.randomUUID();
        EventSeatMapResponse response = new EventSeatMapResponse(eventId, UUID.randomUUID(), "Hamlet",
                Instant.now(), "Grand Hall", 500, 10L, List.of(
                        new SeatMapSectionResponse(UUID.randomUUID(), "A", 5, 10, List.of(), List.of())));
        when(eventService.getEventSeatMap(eventId)).thenReturn(response);

        mockMvc.perform(get("/api/events/{id}/seat-map", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.venueName").value("Grand Hall"));
    }

    @Test
    void getSeatMap_clientUnavailable_returns503() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(eventService.getEventSeatMap(eventId))
                .thenThrow(new SeatMapClientUnavailableException("Seat-map service unavailable"));

        mockMvc.perform(get("/api/events/{id}/seat-map", eventId))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void listEvents_invalidPageSize_returns400() throws Exception {
        mockMvc.perform(get("/api/events").param("size", "200"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listEvents_invalidSort_returns400() throws Exception {
        mockMvc.perform(get("/api/events").param("sort", "status"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listEvents_searchTooLong_returns400() throws Exception {
        mockMvc.perform(get("/api/events").param("search", "x".repeat(101)))
                .andExpect(status().isBadRequest());
    }
}
