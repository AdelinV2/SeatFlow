package com.seatflow.seatmap.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.security.SecurityRoles;
import com.seatflow.common.security.converter.JwtRoleConverter;
import com.seatflow.seatmap.config.SecurityConfig;
import com.seatflow.seatmap.service.VenueSectionService;
import com.seatflow.seatmap.service.VenueService;
import com.seatflow.seatmap.web.dto.request.CreateVenueRequest;
import com.seatflow.seatmap.web.dto.request.CreateVenueSectionRequest;
import com.seatflow.seatmap.web.dto.response.VenueResponse;
import com.seatflow.seatmap.web.dto.response.VenueSectionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminVenueController.class)
@Import(SecurityConfig.class)
class AdminVenueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private VenueService venueService;

    @MockitoBean
    private VenueSectionService sectionService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private JwtRoleConverter jwtRoleConverter;

    @Test
    void shouldCreateVenueForAdmin() throws Exception {
        UUID venueId = UUID.randomUUID();
        CreateVenueRequest request = new CreateVenueRequest("Grand Theatre", "123 Main St", "NYC", "USA", 500);
        VenueResponse response = new VenueResponse(venueId, "Grand Theatre", "123 Main St",
                "NYC", "USA", 500, Instant.now());

        when(venueService.createVenue(any(CreateVenueRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/admin/venues")
                        .with(user("admin").roles(SecurityRoles.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Grand Theatre"))
                .andExpect(jsonPath("$.capacity").value(500));
    }

    @Test
    void shouldReject403ForNonAdminUser() throws Exception {
        CreateVenueRequest request = new CreateVenueRequest("Theatre", "123 St", "NYC", "USA", 100);

        mockMvc.perform(post("/api/admin/venues")
                        .with(user("customer").roles(SecurityRoles.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReject401WhenUnauthenticated() throws Exception {
        CreateVenueRequest request = new CreateVenueRequest("Theatre", "123 St", "NYC", "USA", 100);

        mockMvc.perform(post("/api/admin/venues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldCreateSectionWithSeatGrid() throws Exception {
        UUID venueId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        CreateVenueSectionRequest request = new CreateVenueSectionRequest("Orchestra", 5, 10);
        VenueSectionResponse response = new VenueSectionResponse(sectionId, "Orchestra", 5, 10, 50L);

        when(sectionService.createSection(eq(venueId), any(CreateVenueSectionRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/admin/venues/{venueId}/sections", venueId)
                        .with(user("admin").roles(SecurityRoles.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Orchestra"))
                .andExpect(jsonPath("$.activeSeatCount").value(50));
    }

    @Test
    void shouldDeleteSectionForAdmin() throws Exception {
        UUID venueId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                                "/api/admin/venues/{venueId}/sections/{sectionId}", venueId, sectionId)
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isNoContent());

        org.mockito.Mockito.verify(sectionService).deleteSection(venueId, sectionId);
    }
}
