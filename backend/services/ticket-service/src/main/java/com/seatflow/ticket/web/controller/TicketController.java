package com.seatflow.ticket.web.controller;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.common.security.SecurityRoles;
import com.seatflow.common.security.context.UserContext;
import com.seatflow.ticket.service.TicketService;
import com.seatflow.ticket.web.dto.response.TicketDetailResponse;
import com.seatflow.ticket.web.dto.response.TicketResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
@Validated
@Tag(name = "Tickets", description = "Customer and guest digital ticket delivery APIs")
public class TicketController {

    private final TicketService ticketService;

    @GetMapping("/my-tickets")
    @Operation(summary = "List tickets for authenticated user", description = "Retrieves paginated tickets belonging to the current user")
    @ApiResponse(responseCode = "200", description = "Tickets retrieved successfully")
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<PagedResult<TicketResponse>> getMyTickets(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        UUID userId = UserContext.getCurrentUserIdAsUuid()
                .orElseThrow(() -> new BusinessException("User authentication required", ErrorCode.UNAUTHORIZED, 401));

        UserContext.getCurrentUserEmail().ifPresent(email -> {
            if (!email.isBlank()) {
                ticketService.claimGuestTickets(userId, email.trim());
            }
        });

        PagedResult<TicketResponse> result = ticketService.getMyTickets(userId, PageRequest.of(page, size));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/guest/{ticketCode}")
    @Operation(summary = "Retrieve ticket by guest code", description = "Public endpoint for guest ticket delivery via unique token (ADR-001)")
    @ApiResponse(responseCode = "200", description = "Ticket details with QR payload")
    @ApiResponse(responseCode = "404", description = "Ticket code not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<TicketDetailResponse> getGuestTicket(@PathVariable String ticketCode) {
        TicketDetailResponse response = ticketService.getGuestTicketByCode(ticketCode);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/guest/{ticketCode}/bundle")
    @Operation(summary = "Retrieve all tickets in guest reservation bundle", description = "Returns all tickets associated with the reservation for multi-seat guest orders (ADR-001)")
    @ApiResponse(responseCode = "200", description = "List of ticket details in reservation bundle")
    @ApiResponse(responseCode = "404", description = "Ticket code not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<List<TicketDetailResponse>> getGuestTicketBundle(@PathVariable String ticketCode) {
        List<TicketDetailResponse> response = ticketService.getGuestTicketBundleByCode(ticketCode);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/guest/{ticketCode}/pdf")
    @Operation(summary = "Download guest ticket PDF", description = "Secure guest PDF delivery verified by ticketCode token (ADR-001)")
    @ApiResponse(responseCode = "200", description = "PDF stream returned", content = @Content(mediaType = "application/pdf"))
    @ApiResponse(responseCode = "404", description = "Ticket code not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<byte[]> downloadGuestTicketPdf(@PathVariable String ticketCode) {
        byte[] pdfBytes = ticketService.generateGuestTicketPdf(ticketCode);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"ticket-" + ticketCode + ".pdf\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(pdfBytes);
    }

    @GetMapping("/{ticketId}")
    @Operation(summary = "Retrieve ticket details by ID", description = "Customer or admin view of ticket with QR code data")
    @ApiResponse(responseCode = "200", description = "Ticket details retrieved")
    @ApiResponse(responseCode = "403", description = "Access forbidden", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Ticket not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<TicketDetailResponse> getTicketById(@PathVariable UUID ticketId) {
        UUID userId = UserContext.getCurrentUserIdAsUuid().orElse(null);
        boolean isAdmin = UserContext.hasRole(SecurityRoles.ROLE_ADMIN);

        TicketDetailResponse response = ticketService.getTicketById(ticketId, userId, isAdmin);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{ticketId}/pdf")
    @Operation(summary = "Download ticket as PDF", description = "Renders and streams official PDF ticket with embedded QR code and fiscal breakdown")
    @ApiResponse(responseCode = "200", description = "PDF stream returned", content = @Content(mediaType = "application/pdf"))
    @ApiResponse(responseCode = "404", description = "Ticket not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<byte[]> downloadTicketPdf(@PathVariable UUID ticketId) {
        UUID userId = UserContext.getCurrentUserIdAsUuid().orElse(null);
        boolean isAdmin = UserContext.hasRole(SecurityRoles.ROLE_ADMIN);

        byte[] pdfBytes = ticketService.generateTicketPdf(ticketId, userId, isAdmin);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"ticket-" + ticketId + ".pdf\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(pdfBytes);
    }
}
