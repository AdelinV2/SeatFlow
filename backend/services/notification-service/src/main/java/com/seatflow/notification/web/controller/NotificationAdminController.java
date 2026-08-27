package com.seatflow.notification.web.controller;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.notification.model.enums.NotificationStatus;
import com.seatflow.notification.service.NotificationService;
import com.seatflow.notification.web.dto.response.NotificationLogResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/notifications")
@RequiredArgsConstructor
@Validated
@Tag(name = "Notification Administration", description = "Admin audit and query APIs for transactional notification logs")
public class NotificationAdminController {

    private final NotificationService notificationService;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Query notification logs", description = "Returns a paginated list of notification delivery logs with optional recipient or status filtering")
    @ApiResponse(responseCode = "200", description = "Notification logs retrieved successfully")
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Access forbidden — Requires ROLE_ADMIN", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<PagedResult<NotificationLogResponse>> getNotifications(
            @RequestParam(required = false) String recipientEmail,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        PagedResult<NotificationLogResponse> result =
                notificationService.getNotifications(recipientEmail, status, PageRequest.of(page, size));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Get notification log by ID", description = "Returns detailed delivery and error status for a specific notification record")
    @ApiResponse(responseCode = "200", description = "Notification log found and returned")
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Access forbidden — Requires ROLE_ADMIN", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Notification record not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<NotificationLogResponse> getNotificationById(@PathVariable UUID id) {
        NotificationLogResponse response = notificationService.getNotificationById(id);
        return ResponseEntity.ok(response);
    }
}
