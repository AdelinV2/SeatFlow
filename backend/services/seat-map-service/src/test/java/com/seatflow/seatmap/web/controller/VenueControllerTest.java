package com.seatflow.seatmap.web.controller;

import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.security.converter.JwtRoleConverter;
import com.seatflow.seatmap.config.SecurityConfig;
import com.seatflow.seatmap.service.SeatMapLayoutService;
import com.seatflow.seatmap.service.VenueService;
import com.seatflow.seatmap.web.dto.response.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(VenueController.class)
@Import(SecurityConfig.class)
class VenueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VenueService venueService;

    @MockitoBean
    private SeatMapLayoutService seatMapLayoutService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private JwtRoleConverter jwtRoleConverter;

    @Test
    void shouldListVenuesWithoutAuthentication() throws Exception {
        UUID venueId = UUID.randomUUID();
        VenueResponse response = new VenueResponse(venueId, "Theatre", "123 St", "NYC", "USA", 500, Instant.now());
        PagedResult<VenueResponse> result = PagedResult.of(List.of(response), 0, 20, 1);

        when(venueService.listVenues(any(), any(), any())).thenReturn(result);

        mockMvc.perform(get("/api/venues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Theatre"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void shouldGetVenueDetailWithoutAuthentication() throws Exception {
        UUID venueId = UUID.randomUUID();
        VenueDetailResponse response = new VenueDetailResponse(venueId, "Theatre", "123 St",
                "NYC", "USA", 500, 0L, List.of(), Instant.now());

        when(venueService.getVenueById(venueId)).thenReturn(response);

        mockMvc.perform(get("/api/venues/{venueId}", venueId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Theatre"));
    }

    @Test
    void shouldGetVenueLayoutWithoutAuthentication() throws Exception {
        UUID venueId = UUID.randomUUID();
        VenueSeatMapLayoutResponse response = new VenueSeatMapLayoutResponse(venueId, "Theatre", 500, 0L, List.of());

        when(seatMapLayoutService.getVenueLayout(venueId)).thenReturn(response);

        mockMvc.perform(get("/api/venues/{venueId}/layout", venueId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venueId").value(venueId.toString()))
                .andExpect(jsonPath("$.name").value("Theatre"));
    }
}
