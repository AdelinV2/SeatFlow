package com.seatflow.seatmap.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.security.SecurityRoles;
import com.seatflow.common.security.converter.JwtRoleConverter;
import com.seatflow.seatmap.config.SecurityConfig;
import com.seatflow.seatmap.service.VenueLayoutService;
import com.seatflow.seatmap.service.VenueSectionService;
import com.seatflow.seatmap.service.VenueService;
import com.seatflow.seatmap.web.dto.request.CreateVenueRequest;
import com.seatflow.seatmap.web.dto.request.CreateVenueSectionRequest;
import com.seatflow.seatmap.web.dto.request.SaveVenueLayoutRequest;
import com.seatflow.seatmap.web.dto.response.VenueResponse;
import com.seatflow.seatmap.web.dto.response.VenueSeatMapLayoutResponse;
import com.seatflow.seatmap.web.dto.response.VenueSectionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.seatflow.seatmap.web.dto.response.SectionLayoutResponse;

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
    private VenueLayoutService layoutService;

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

    private SaveVenueLayoutRequest emptyLayoutRequest(long version) {
        return new SaveVenueLayoutRequest(version, List.of(), List.of());
    }

    private VenueSeatMapLayoutResponse layoutResponse(UUID venueId, long version) {
        return new VenueSeatMapLayoutResponse(venueId, "Hall", 100, 0L, List.of(), version, List.of());
    }

    @Test
    void shouldGetEditableLayoutForAdmin() throws Exception {
        UUID venueId = UUID.randomUUID();
        when(layoutService.getEditableLayout(venueId)).thenReturn(layoutResponse(venueId, 7L));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/admin/venues/{venueId}/layout", venueId)
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venueId").value(venueId.toString()))
                .andExpect(jsonPath("$.layoutVersion").value(7));
    }

    @Test
    void shouldReject403OnLayoutReadForNonAdmin() throws Exception {
        UUID venueId = UUID.randomUUID();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/admin/venues/{venueId}/layout", venueId)
                        .with(user("customer").roles(SecurityRoles.CUSTOMER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReject401OnLayoutReadWhenUnauthenticated() throws Exception {
        UUID venueId = UUID.randomUUID();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/admin/venues/{venueId}/layout", venueId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldValidateLayoutForAdminWith204() throws Exception {
        UUID venueId = UUID.randomUUID();

        mockMvc.perform(post("/api/admin/venues/{venueId}/layout/validation", venueId)
                        .with(user("admin").roles(SecurityRoles.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyLayoutRequest(3L))))
                .andExpect(status().isNoContent());

        org.mockito.Mockito.verify(layoutService)
                .validateLayout(eq(venueId), any(SaveVenueLayoutRequest.class));
    }

    @Test
    void shouldReject403OnLayoutValidationForNonAdmin() throws Exception {
        UUID venueId = UUID.randomUUID();

        mockMvc.perform(post("/api/admin/venues/{venueId}/layout/validation", venueId)
                        .with(user("customer").roles(SecurityRoles.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyLayoutRequest(3L))))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReject401OnLayoutValidationWhenUnauthenticated() throws Exception {
        UUID venueId = UUID.randomUUID();

        mockMvc.perform(post("/api/admin/venues/{venueId}/layout/validation", venueId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyLayoutRequest(3L))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldSaveLayoutForAdmin() throws Exception {
        UUID venueId = UUID.randomUUID();
        when(layoutService.saveLayout(eq(venueId), any(SaveVenueLayoutRequest.class)))
                .thenReturn(layoutResponse(venueId, 8L));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/admin/venues/{venueId}/layout", venueId)
                        .with(user("admin").roles(SecurityRoles.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyLayoutRequest(7L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layoutVersion").value(8));
    }

    @Test
    void shouldSaveLayoutWithShapeMetadataForAdmin() throws Exception {
        UUID venueId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        SectionLayoutResponse sectionResponse = new SectionLayoutResponse(
                sectionId, "Orchestra", 5, 10, List.of(), true,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("50"),
                BigDecimal.ZERO, 0, objectMapper.readTree("{\"color\": \"#6366f1\", \"seatColors\": {\"A_1\": \"#059669\"}}"));
        VenueSeatMapLayoutResponse savedResponse = new VenueSeatMapLayoutResponse(
                venueId, "Hall", 100, 0L, List.of(sectionResponse), 9L, List.of());
        when(layoutService.saveLayout(eq(venueId), any(SaveVenueLayoutRequest.class)))
                .thenReturn(savedResponse);

        String requestBody = """
                {
                  "layoutVersion": 8,
                  "sections": [
                    {
                      "sectionId": null,
                      "name": "Orchestra",
                      "rowCount": 5,
                      "colCount": 10,
                      "isActive": true,
                      "positionX": 0,
                      "positionY": 0,
                      "width": 100,
                      "height": 50,
                      "rotationDeg": 0,
                      "zIndex": 0,
                      "shapeMetadata": {
                        "color": "#6366f1",
                        "seatColors": {
                          "A_1": "#059669"
                        }
                      },
                      "seats": []
                    }
                  ],
                  "elements": []
                }
                """;

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/admin/venues/{venueId}/layout", venueId)
                        .with(user("admin").roles(SecurityRoles.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layoutVersion").value(9))
                .andExpect(jsonPath("$.sections[0].shapeMetadata.color").value("#6366f1"))
                .andExpect(jsonPath("$.sections[0].shapeMetadata.seatColors.A_1").value("#059669"));
    }

    @Test
    void shouldReject403OnLayoutSaveForNonAdmin() throws Exception {
        UUID venueId = UUID.randomUUID();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/admin/venues/{venueId}/layout", venueId)
                        .with(user("customer").roles(SecurityRoles.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyLayoutRequest(7L))))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReject401OnLayoutSaveWhenUnauthenticated() throws Exception {
        UUID venueId = UUID.randomUUID();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/admin/venues/{venueId}/layout", venueId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyLayoutRequest(7L))))
                .andExpect(status().isUnauthorized());
    }
}
