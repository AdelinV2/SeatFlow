package com.seatflow.notification.web.controller;

import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.observability.handler.GlobalExceptionHandler;
import com.seatflow.common.security.SecurityRoles;
import com.seatflow.common.security.converter.JwtRoleConverter;
import com.seatflow.notification.config.SecurityConfig;
import com.seatflow.notification.model.enums.NotificationStatus;
import com.seatflow.notification.model.enums.NotificationTemplateType;
import com.seatflow.notification.service.NotificationService;
import com.seatflow.notification.web.dto.response.NotificationLogResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NotificationAdminController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class NotificationAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private JwtRoleConverter jwtRoleConverter;

    @Test
    @WithMockUser(authorities = SecurityRoles.ROLE_ADMIN)
    @DisplayName("Should return paginated notification logs when queried by admin")
    void shouldReturnPaginatedLogsForAdmin() throws Exception {
        UUID id = UUID.randomUUID();
        NotificationLogResponse logResponse = new NotificationLogResponse(
                id,
                "user@example.com",
                NotificationTemplateType.TICKET_ISSUED,
                "Your Ticket",
                "ticket-issued-123",
                "<html>Ticket Body</html>",
                NotificationStatus.SENT,
                null,
                Instant.now(),
                0,
                Instant.now(),
                Instant.now()
        );

        PagedResult<NotificationLogResponse> pagedResult =
                PagedResult.of(List.of(logResponse), 0, 20, 1);

        when(notificationService.getNotifications(eq("user@example.com"), eq(NotificationStatus.SENT), any(PageRequest.class)))
                .thenReturn(pagedResult);

        mockMvc.perform(get("/api/admin/notifications")
                        .param("recipientEmail", "user@example.com")
                        .param("status", "SENT")
                        .param("page", "0")
                        .param("size", "20")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.content[0].recipientEmail").value("user@example.com"))
                .andExpect(jsonPath("$.content[0].status").value("SENT"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @WithMockUser(authorities = SecurityRoles.ROLE_CUSTOMER)
    @DisplayName("Should return 403 Forbidden when non-admin accesses admin notifications")
    void shouldReturn403ForCustomer() throws Exception {
        mockMvc.perform(get("/api/admin/notifications")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Should return 401 Unauthorized when unauthenticated request arrives")
    void shouldReturn401ForUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/notifications")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(authorities = SecurityRoles.ROLE_ADMIN)
    @DisplayName("Should return notification log by ID for admin")
    void shouldReturnNotificationLogById() throws Exception {
        UUID id = UUID.randomUUID();
        NotificationLogResponse logResponse = new NotificationLogResponse(
                id,
                "test@example.com",
                NotificationTemplateType.PAYMENT_FAILED,
                "Payment Failed",
                "payment-failed-123",
                "<html>Failed Body</html>",
                NotificationStatus.FAILED,
                "Card declined",
                null,
                1,
                Instant.now(),
                Instant.now()
        );

        when(notificationService.getNotificationById(id)).thenReturn(logResponse);

        mockMvc.perform(get("/api/admin/notifications/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorMessage").value("Card declined"));
    }

    @Test
    @WithMockUser(authorities = SecurityRoles.ROLE_ADMIN)
    @DisplayName("Should return 404 when notification log ID not found")
    void shouldReturn404WhenNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(notificationService.getNotificationById(id))
                .thenThrow(new ResourceNotFoundException("Notification record not found with id: " + id));

        mockMvc.perform(get("/api/admin/notifications/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }
}
