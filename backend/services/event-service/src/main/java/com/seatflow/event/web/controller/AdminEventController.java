package com.seatflow.event.web.controller;

import com.seatflow.event.service.EventPricingService;
import com.seatflow.event.service.EventService;
import com.seatflow.event.web.dto.request.ConfigurePricingRequest;
import com.seatflow.event.web.dto.request.CreateEventRequest;
import com.seatflow.event.web.dto.request.UpdateEventRequest;
import com.seatflow.event.web.dto.response.EventDetailResponse;
import com.seatflow.event.web.dto.response.PricingTierResponse;
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
@RequestMapping("/api/admin/events")
@RequiredArgsConstructor
@Tag(name = "Events (Administration)", description = "Administrative event lifecycle and pricing APIs")
public class AdminEventController {

    private final EventService eventService;
    private final EventPricingService eventPricingService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a draft event", description = "Creates a DRAFT event after validating the referenced venue.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Draft event created",
                content = @Content(schema = @Schema(implementation = EventDetailResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request or venue",
                content = @Content(schema = @Schema(implementation = EventDetailResponse.class)))
    })
    public ResponseEntity<EventDetailResponse> createEvent(@Valid @RequestBody CreateEventRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(eventService.createEvent(request));
    }

    @GetMapping("/{eventId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get event details for administration",
            description = "Returns full event metadata including draft/cancelled/completed status.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Event retrieved",
                content = @Content(schema = @Schema(implementation = EventDetailResponse.class))),
        @ApiResponse(responseCode = "404", description = "Event not found",
                content = @Content(schema = @Schema(implementation = EventDetailResponse.class)))
    })
    public ResponseEntity<EventDetailResponse> getEvent(@PathVariable UUID eventId) {
        return ResponseEntity.ok(eventService.getEventForAdministration(eventId));
    }

    @PutMapping("/{eventId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update an event or transition its status",
            description = "Applies a partial metadata update and validates lifecycle transitions.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Event updated",
                content = @Content(schema = @Schema(implementation = EventDetailResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid update or state transition",
                content = @Content(schema = @Schema(implementation = EventDetailResponse.class))),
        @ApiResponse(responseCode = "404", description = "Event not found",
                content = @Content(schema = @Schema(implementation = EventDetailResponse.class)))
    })
    public ResponseEntity<EventDetailResponse> updateEvent(@PathVariable UUID eventId,
                                                          @Valid @RequestBody UpdateEventRequest request) {
        return ResponseEntity.ok(eventService.updateEvent(eventId, request));
    }

    @PostMapping("/{eventId}/pricing")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Replace event section pricing",
            description = "Validates section ownership and atomically replaces pricing tiers for an editable event.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Pricing configured",
                content = @Content(schema = @Schema(implementation = PricingTierResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid pricing or immutable event",
                content = @Content(schema = @Schema(implementation = PricingTierResponse.class)))
    })
    public ResponseEntity<List<PricingTierResponse>> configurePricing(@PathVariable UUID eventId,
                                                                     @Valid @RequestBody ConfigurePricingRequest request) {
        return ResponseEntity.ok(eventPricingService.configurePricing(eventId, request));
    }
}
