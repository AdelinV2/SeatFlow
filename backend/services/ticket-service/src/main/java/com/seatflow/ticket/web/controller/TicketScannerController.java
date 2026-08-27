package com.seatflow.ticket.web.controller;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.ticket.service.TicketService;
import com.seatflow.ticket.web.dto.request.ValidateTicketRequest;
import com.seatflow.ticket.web.dto.response.ValidationResultResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scanner/tickets")
@RequiredArgsConstructor
@Tag(name = "Scanner Tickets", description = "Venue gate scanner check-in APIs for operational staff and admins (ADR-005)")
public class TicketScannerController {

    private final TicketService ticketService;

    @PostMapping("/validate")
    @Operation(summary = "Validate ticket at entrance gate", description = "Scans ticket code, validates status, records validation audit log, and marks ticket USED")
    @ApiResponse(responseCode = "200", description = "Validation result returned")
    @ApiResponse(responseCode = "400", description = "Invalid request payload", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Forbidden — requires ROLE_STAFF or ROLE_ADMIN", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ValidationResultResponse> validateTicket(
            @Valid @RequestBody ValidateTicketRequest request) {
        ValidationResultResponse response = ticketService.validateTicket(request);
        return ResponseEntity.ok(response);
    }
}
