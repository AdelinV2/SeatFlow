package com.seatflow.event.web.controller;

import com.seatflow.event.service.EventSessionService;
import com.seatflow.event.web.dto.request.CreateEventSessionRequest;
import com.seatflow.event.web.dto.request.UpdateEventSessionRequest;
import com.seatflow.event.web.dto.response.EventSessionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/events/{eventId}/sessions")
@RequiredArgsConstructor
@Tag(name = "Event Sessions (Administration)", description = "Organizer session schedule management APIs")
public class AdminEventSessionController {

    private final EventSessionService eventSessionService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create an event session",
            description = "Creates one SCHEDULED session for the given event. The parent event is derived from the path.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Session created",
                content = @Content(schema = @Schema(implementation = EventSessionResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid schedule",
                content = @Content(schema = @Schema(implementation = EventSessionResponse.class))),
        @ApiResponse(responseCode = "404", description = "Event not found",
                content = @Content(schema = @Schema(implementation = EventSessionResponse.class)))
    })
    public ResponseEntity<EventSessionResponse> createSession(@PathVariable UUID eventId,
                                                              @Valid @RequestBody CreateEventSessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(eventSessionService.createSession(eventId, request));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List all event sessions for administration",
            description = "Returns every session of the event in schedule order, including cancelled and past sessions.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Sessions retrieved",
                content = @Content(schema = @Schema(implementation = EventSessionResponse.class))),
        @ApiResponse(responseCode = "404", description = "Event not found",
                content = @Content(schema = @Schema(implementation = EventSessionResponse.class)))
    })
    public ResponseEntity<List<EventSessionResponse>> listSessions(@PathVariable UUID eventId) {
        return ResponseEntity.ok(eventSessionService.listSessionsForAdmin(eventId));
    }

    @PutMapping("/{sessionId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update an event session schedule",
            description = "Updates mutable schedule fields while the session is unlocked. Rejects event/session mismatches.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Session updated",
                content = @Content(schema = @Schema(implementation = EventSessionResponse.class))),
        @ApiResponse(responseCode = "404", description = "Event or session not found, or session belongs to another event",
                content = @Content(schema = @Schema(implementation = EventSessionResponse.class))),
        @ApiResponse(responseCode = "409", description = "Session schedule is locked once sales are open",
                content = @Content(schema = @Schema(implementation = EventSessionResponse.class)))
    })
    public ResponseEntity<EventSessionResponse> updateSession(@PathVariable UUID eventId,
                                                              @PathVariable UUID sessionId,
                                                              @Valid @RequestBody UpdateEventSessionRequest request) {
        return ResponseEntity.ok(eventSessionService.updateSession(eventId, sessionId, request));
    }

    @DeleteMapping("/{sessionId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete an event session",
            description = "Deletes an unlocked session. Rejected once the session schedule is sales-locked.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Session deleted"),
        @ApiResponse(responseCode = "404", description = "Event or session not found, or session belongs to another event"),
        @ApiResponse(responseCode = "409", description = "Session schedule is locked once sales are open")
    })
    public ResponseEntity<Void> deleteSession(@PathVariable UUID eventId,
                                              @PathVariable UUID sessionId) {
        eventSessionService.deleteSession(eventId, sessionId);
        return ResponseEntity.noContent().build();
    }
}
