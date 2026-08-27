package com.seatflow.ticket.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request body for validating ticket QR code at venue entrance")
public record ValidateTicketRequest(

    @Schema(description = "Unique ticket verification code", example = "SF-TKT-9876-ABCD")
    @NotBlank(message = "Ticket code is required")
    String ticketCode,

    @Schema(description = "Identifier of scanning device", example = "GATE-SOUTH-SCANNER-01")
    @NotBlank(message = "Scanner device ID is required")
    String scannerDeviceId

) {}
