package com.seatflow.ticket.web.controller;

import com.seatflow.common.observability.handler.GlobalExceptionHandler;
import com.seatflow.common.security.SecurityRoles;
import com.seatflow.ticket.config.SecurityConfig;
import com.seatflow.ticket.model.enums.ValidationResult;
import com.seatflow.ticket.service.TicketService;
import com.seatflow.ticket.web.dto.request.ValidateTicketRequest;
import com.seatflow.ticket.web.dto.response.ValidationResultResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TicketScannerController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class TicketScannerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TicketService ticketService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private com.seatflow.common.security.converter.JwtRoleConverter jwtRoleConverter;

    private static final String VALID_BODY = """
            {
              "ticketCode": "SF-TKT-1234",
              "scannerDeviceId": "GATE-1"
            }
            """;

    @Test
    void validateTicket_withStaffRole_returns200() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.validateTicket(any(ValidateTicketRequest.class)))
                .thenReturn(new ValidationResultResponse(true, ticketId, "SF-TKT-1234", ValidationResult.SUCCESS,
                        "Concert", Instant.now(), "Jane Doe", "A", "1", 12, "Standard", Instant.now(), "Entry granted"));

        mockMvc.perform(post("/api/scanner/tickets/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY)
                        .with(jwt().authorities(new SimpleGrantedAuthority(SecurityRoles.ROLE_STAFF))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.ticketType").value("Standard"));
    }

    @Test
    void validateTicket_withAdminRole_returns200() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.validateTicket(any(ValidateTicketRequest.class)))
                .thenReturn(new ValidationResultResponse(true, ticketId, "SF-TKT-1234", ValidationResult.SUCCESS,
                        "Concert", Instant.now(), "Jane Doe", "A", "1", 12, "VIP", Instant.now(), "Entry granted"));

        mockMvc.perform(post("/api/scanner/tickets/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY)
                        .with(jwt().authorities(new SimpleGrantedAuthority(SecurityRoles.ROLE_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.ticketType").value("VIP"));
    }

    @Test
    void validateTicket_withCustomerRole_returns403() throws Exception {
        mockMvc.perform(post("/api/scanner/tickets/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY)
                        .with(jwt().authorities(new SimpleGrantedAuthority(SecurityRoles.ROLE_CUSTOMER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void validateTicket_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/scanner/tickets/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validateTicket_withBlankPayload_returns400() throws Exception {
        String blankBody = """
                {
                  "ticketCode": "",
                  "scannerDeviceId": ""
                }
                """;

        mockMvc.perform(post("/api/scanner/tickets/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blankBody)
                        .with(jwt().authorities(new SimpleGrantedAuthority(SecurityRoles.ROLE_STAFF))))
                .andExpect(status().isBadRequest());
    }
}
