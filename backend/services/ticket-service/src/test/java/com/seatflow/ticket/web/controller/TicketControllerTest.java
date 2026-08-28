package com.seatflow.ticket.web.controller;

import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.common.observability.handler.GlobalExceptionHandler;
import com.seatflow.common.security.SecurityRoles;
import com.seatflow.ticket.config.SecurityConfig;
import com.seatflow.ticket.service.TicketService;
import com.seatflow.ticket.web.dto.response.TicketDetailResponse;
import com.seatflow.ticket.web.dto.response.TicketResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TicketController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class TicketControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TicketService ticketService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private com.seatflow.common.security.converter.JwtRoleConverter jwtRoleConverter;

    @Test
    void getMyTickets_withCustomerRole_returns200() throws Exception {
        UUID userId = UUID.randomUUID();
        when(ticketService.getMyTickets(any(UUID.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(PagedResult.of(List.of(org.mockito.Mockito.mock(TicketResponse.class)), 0, 10, 1));

        mockMvc.perform(get("/api/tickets/my-tickets")
                        .with(jwt().jwt(j -> j.subject(userId.toString()))
                                .authorities(new SimpleGrantedAuthority(SecurityRoles.ROLE_CUSTOMER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getMyTickets_withAdminRole_returns200() throws Exception {
        UUID userId = UUID.randomUUID();
        when(ticketService.getMyTickets(any(UUID.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(PagedResult.of(List.of(org.mockito.Mockito.mock(TicketResponse.class)), 0, 10, 1));

        mockMvc.perform(get("/api/tickets/my-tickets")
                        .with(jwt().jwt(j -> j.subject(userId.toString()))
                                .authorities(new SimpleGrantedAuthority(SecurityRoles.ROLE_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getMyTickets_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/tickets/my-tickets"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getMyTickets_withInvalidPage_returns400() throws Exception {
        mockMvc.perform(get("/api/tickets/my-tickets").param("page", "-1")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                                .authorities(new SimpleGrantedAuthority(SecurityRoles.ROLE_CUSTOMER))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getGuestTicket_publicAccess_returns200() throws Exception {
        when(ticketService.getGuestTicketByCode("SF-TKT-1234"))
                .thenReturn(org.mockito.Mockito.mock(TicketDetailResponse.class));

        mockMvc.perform(get("/api/tickets/guest/SF-TKT-1234"))
                .andExpect(status().isOk());
    }

    @Test
    void downloadPdf_authenticatedCustomer_returns200() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.generateTicketPdf(any(UUID.class), nullable(UUID.class), anyBoolean()))
                .thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(get("/api/tickets/{ticketId}/pdf", ticketId)
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                                .authorities(new SimpleGrantedAuthority(SecurityRoles.ROLE_CUSTOMER))))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(new byte[]{1, 2, 3}))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("ticket-" + ticketId + ".pdf")));
    }

    @Test
    void downloadPdf_unauthenticatedGuest_returns200() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.generateTicketPdf(eq(ticketId), nullable(UUID.class), anyBoolean()))
                .thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(get("/api/tickets/{ticketId}/pdf", ticketId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(new byte[]{1, 2, 3}))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("ticket-" + ticketId + ".pdf")));
    }

    @Test
    void downloadPdf_unauthenticatedForbiddenTicket_returns403() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.generateTicketPdf(eq(ticketId), nullable(UUID.class), anyBoolean()))
                .thenThrow(new BusinessException("Access denied to ticket PDF", ErrorCode.FORBIDDEN, 403));

        mockMvc.perform(get("/api/tickets/{ticketId}/pdf", ticketId))
                .andExpect(status().isForbidden());
    }
}
