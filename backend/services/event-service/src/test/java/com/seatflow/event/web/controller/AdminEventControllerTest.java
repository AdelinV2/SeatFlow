package com.seatflow.event.web.controller;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.observability.handler.GlobalExceptionHandler;
import com.seatflow.event.config.SecurityConfig;
import com.seatflow.event.model.enums.EventCategory;
import com.seatflow.event.model.enums.EventStatus;
import com.seatflow.event.service.EventPricingService;
import com.seatflow.event.service.EventService;
import com.seatflow.event.web.dto.response.EventDetailResponse;
import com.seatflow.event.web.dto.response.PricingTierResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminEventController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AdminEventControllerTest {

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

    private static String validCreateBody() {
        return String.format(
                "{\"venueId\":\"%s\",\"title\":\"Hamlet\",\"description\":\"A play\",\"category\":\"OTHER\",\"eventDate\":\"2027-05-01T19:30:00Z\"}",
                UUID.randomUUID());
    }

    private static EventDetailResponse sampleDetail(UUID id) {
        return new EventDetailResponse(id, UUID.randomUUID(), "Hamlet", "desc", EventCategory.OTHER, null,
                Instant.now(), EventStatus.DRAFT, List.of(), Instant.now(), Instant.now());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createEvent_admin_returns201() throws Exception {
        UUID id = UUID.randomUUID();
        when(eventService.createEvent(any())).thenReturn(sampleDetail(id));

        mockMvc.perform(post("/api/admin/events").contentType(MediaType.APPLICATION_JSON).content(validCreateBody()))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getEvent_admin_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(eventService.getEventForAdministration(id)).thenReturn(sampleDetail(id));

        mockMvc.perform(get("/api/admin/events/{id}", id))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateEvent_admin_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(eventService.updateEvent(any(), any())).thenReturn(sampleDetail(id));

        mockMvc.perform(put("/api/admin/events/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Hamlet 2\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void configurePricing_admin_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(eventPricingService.configurePricing(any(), any())).thenReturn(List.of(
                new PricingTierResponse(UUID.randomUUID(), UUID.randomUUID(), "VIP", new BigDecimal("50.00"), "USD")));

        mockMvc.perform(post("/api/admin/events/{id}/pricing", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pricingTiers\":[{\"sectionId\":\"" + UUID.randomUUID()
                                + "\",\"categoryName\":\"VIP\",\"price\":50.00,\"currency\":\"USD\"}]}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createEvent_invalidBody_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/events").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void createEvent_customer_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/events").contentType(MediaType.APPLICATION_JSON).content(validCreateBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void createEvent_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/admin/events").contentType(MediaType.APPLICATION_JSON).content(validCreateBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createEvent_stateValidation_returns400() throws Exception {
        when(eventService.createEvent(any())).thenThrow(
                new ValidationException("Invalid lifecycle state", ErrorCode.INVALID_REQUEST));

        mockMvc.perform(post("/api/admin/events").contentType(MediaType.APPLICATION_JSON).content(validCreateBody()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getEvent_serviceNotFound_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(eventService.getEventForAdministration(id)).thenThrow(new ResourceNotFoundException("Event", id));

        mockMvc.perform(get("/api/admin/events/{id}", id))
                .andExpect(status().isNotFound());
    }
}
