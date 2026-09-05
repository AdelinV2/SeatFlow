package com.seatflow.event.web.controller;

import com.seatflow.event.service.EventSessionService;
import com.seatflow.event.web.dto.response.SessionBookingContextResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/event-sessions")
@RequiredArgsConstructor
@Tag(name = "Event Sessions (Internal)", description = "Authenticated inter-service session validation APIs")
public class InternalEventSessionController {

    private final EventSessionService eventSessionService;

    @GetMapping("/{sessionId}/booking-context")
    @Operation(summary = "Resolve session booking context",
            description = "Returns the exact selected session with its parent event facts derived server-side.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Booking context resolved",
                content = @Content(schema = @Schema(implementation = SessionBookingContextResponse.class))),
        @ApiResponse(responseCode = "404", description = "Session not found",
                content = @Content(schema = @Schema(implementation = SessionBookingContextResponse.class)))
    })
    public ResponseEntity<SessionBookingContextResponse> getBookingContext(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(eventSessionService.getBookingContext(sessionId));
    }
}
