package com.seatflow.event.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import com.seatflow.event.web.dto.response.SeatMapSeatResponse;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
        UUID venueId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID elementId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        Instant eventDate = Instant.parse("2030-06-15T19:30:00Z");
        EventSeatMapResponse response = new EventSeatMapResponse(eventId, venueId, "Hamlet", "PUBLISHED",
                eventDate, "Grand Hall", 500, 10L,
                List.of(new SeatMapSectionResponse(sectionId, "Orchestra", 5, 10, true,
                        new BigDecimal("10.5"), new BigDecimal("20.25"),
                        new BigDecimal("440"), new BigDecimal("220"), new BigDecimal("15.5"), 3,
                        Map.of("kind", "rect"),
                        List.of(new SeatMapSeatResponse(seatId, "R1", 7, 1, 2, true,
                                new BigDecimal("44.5"), new BigDecimal("88.25"))),
                        List.of(new PricingTierResponse(tierId, sectionId, "VIP",
                                new BigDecimal("50.00"), "USD")))),
                7L,
                List.of(new EventSeatMapResponse.LayoutElement(elementId, "HOLOGRAM", "Future prop",
                        new EventSeatMapResponse.Geometry(new BigDecimal("1.5"), new BigDecimal("2.5"),
                                new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("90")), 9)));
        when(eventService.getEventSeatMap(eventId)).thenReturn(response);

        String content = mockMvc.perform(get("/api/events/{id}/seat-map", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.venueName").value("Grand Hall"))
                .andReturn().getResponse().getContentAsString();

        JsonNode tree = new ObjectMapper().registerModule(new JavaTimeModule()).readTree(content);
        assertThat(tree.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(tree.get("venueId").asText()).isEqualTo(venueId.toString());
        assertThat(tree.get("eventTitle").asText()).isEqualTo("Hamlet");
        assertThat(tree.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(Instant.parse(tree.get("eventDate").asText())).isEqualTo(eventDate);
        assertThat(tree.get("venueName").asText()).isEqualTo("Grand Hall");
        assertThat(tree.get("venueCapacity").asInt()).isEqualTo(500);
        assertThat(tree.get("totalConfiguredSeats").asLong()).isEqualTo(10L);
        assertThat(tree.get("layoutVersion").asLong()).isEqualTo(7L);

        assertThat(tree.get("sections")).hasSize(1);
        JsonNode section = tree.get("sections").get(0);
        assertThat(section.get("sectionId").asText()).isEqualTo(sectionId.toString());
        assertThat(section.get("name").asText()).isEqualTo("Orchestra");
        assertThat(section.get("rowCount").asInt()).isEqualTo(5);
        assertThat(section.get("colCount").asInt()).isEqualTo(10);
        assertThat(section.get("isActive").asBoolean()).isTrue();
        assertThat(section.get("positionX").decimalValue()).isEqualByComparingTo("10.5");
        assertThat(section.get("positionY").decimalValue()).isEqualByComparingTo("20.25");
        assertThat(section.get("width").decimalValue()).isEqualByComparingTo("440");
        assertThat(section.get("height").decimalValue()).isEqualByComparingTo("220");
        assertThat(section.get("rotationDeg").decimalValue()).isEqualByComparingTo("15.5");
        assertThat(section.get("zIndex").asInt()).isEqualTo(3);
        assertThat(section.get("shapeMetadata").get("kind").asText()).isEqualTo("rect");

        assertThat(section.get("seats")).hasSize(1);
        JsonNode seat = section.get("seats").get(0);
        assertThat(seat.get("seatId").asText()).isEqualTo(seatId.toString());
        assertThat(seat.get("rowLabel").asText()).isEqualTo("R1");
        assertThat(seat.get("seatNumber").asInt()).isEqualTo(7);
        assertThat(seat.get("gridX").asInt()).isEqualTo(1);
        assertThat(seat.get("gridY").asInt()).isEqualTo(2);
        assertThat(seat.get("isActive").asBoolean()).isTrue();
        assertThat(seat.get("positionX").decimalValue()).isEqualByComparingTo("44.5");
        assertThat(seat.get("positionY").decimalValue()).isEqualByComparingTo("88.25");

        assertThat(section.get("pricingTiers")).hasSize(1);
        JsonNode tier = section.get("pricingTiers").get(0);
        assertThat(tier.get("id").asText()).isEqualTo(tierId.toString());
        assertThat(tier.get("sectionId").asText()).isEqualTo(sectionId.toString());
        assertThat(tier.get("categoryName").asText()).isEqualTo("VIP");
        assertThat(tier.get("price").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(tier.get("currency").asText()).isEqualTo("USD");

        assertThat(tree.get("layoutElements")).hasSize(1);
        JsonNode element = tree.get("layoutElements").get(0);
        assertThat(element.get("elementId").asText()).isEqualTo(elementId.toString());
        assertThat(element.get("type").asText()).isEqualTo("HOLOGRAM");
        assertThat(element.get("label").asText()).isEqualTo("Future prop");
        assertThat(element.get("zIndex").asInt()).isEqualTo(9);
        assertThat(element.get("geometry").get("x").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(element.get("geometry").get("y").decimalValue()).isEqualByComparingTo("2.5");
        assertThat(element.get("geometry").get("width").decimalValue()).isEqualByComparingTo("100");
        assertThat(element.get("geometry").get("height").decimalValue()).isEqualByComparingTo("50");
        assertThat(element.get("geometry").get("rotationDeg").decimalValue()).isEqualByComparingTo("90");
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
