package com.seatflow.seatmap.web.controller;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.seatmap.service.VenueSectionService;
import com.seatflow.seatmap.service.VenueService;
import com.seatflow.seatmap.web.dto.request.CreateVenueRequest;
import com.seatflow.seatmap.web.dto.request.CreateVenueSectionRequest;
import com.seatflow.seatmap.web.dto.request.UpdateSeatStatusRequest;
import com.seatflow.seatmap.web.dto.request.UpdateVenueRequest;
import com.seatflow.seatmap.web.dto.response.SeatResponse;
import com.seatflow.seatmap.web.dto.response.VenueResponse;
import com.seatflow.seatmap.web.dto.response.VenueSectionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/venues")
@RequiredArgsConstructor
@Tag(name = "Venues (Admin)", description = "Admin-only venue and section management APIs")
public class AdminVenueController {

    private final VenueService venueService;
    private final VenueSectionService sectionService;

    @PostMapping
    @Operation(
        summary = "Create a new venue",
        description = "Creates a new venue. Rejects duplicate (name, city) combinations."
    )
    @ApiResponse(responseCode = "201", description = "Venue created successfully",
        content = @Content(schema = @Schema(implementation = VenueResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Venue with same name already exists in the city",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Admin role required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<VenueResponse> createVenue(@Valid @RequestBody CreateVenueRequest request) {
        VenueResponse response = venueService.createVenue(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{venueId}")
    @Operation(
        summary = "Update an existing venue",
        description = "Updates venue details. Only non-null fields in the request body are applied."
    )
    @ApiResponse(responseCode = "200", description = "Venue updated successfully",
        content = @Content(schema = @Schema(implementation = VenueResponse.class)))
    @ApiResponse(responseCode = "404", description = "Venue not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Admin role required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<VenueResponse> updateVenue(
            @PathVariable UUID venueId,
            @Valid @RequestBody UpdateVenueRequest request) {
        VenueResponse response = venueService.updateVenue(venueId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{venueId}/sections")
    @Operation(
        summary = "Create a venue section with auto-generated seat grid",
        description = "Creates a new section in the specified venue and automatically generates a rowCount × colCount seat grid. Row labels use alphabetic progression (A, B, C, ..., Z, AA, AB, ...)."
    )
    @ApiResponse(responseCode = "201", description = "Section created with seat grid",
        content = @Content(schema = @Schema(implementation = VenueSectionResponse.class)))
    @ApiResponse(responseCode = "404", description = "Venue not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Section with same name already exists in this venue",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Admin role required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<VenueSectionResponse> createSection(
            @PathVariable UUID venueId,
            @Valid @RequestBody CreateVenueSectionRequest request) {
        VenueSectionResponse response = sectionService.createSection(venueId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{venueId}/sections/{sectionId}/seats/{seatId}")
    @Operation(
        summary = "Toggle seat active/inactive status",
        description = "Activates or deactivates a specific seat within a venue section. Deactivated seats are not bookable."
    )
    @ApiResponse(responseCode = "200", description = "Seat status updated",
        content = @Content(schema = @Schema(implementation = SeatResponse.class)))
    @ApiResponse(responseCode = "404", description = "Venue, section, or seat not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Admin role required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<SeatResponse> updateSeatStatus(
            @PathVariable UUID venueId,
            @PathVariable UUID sectionId,
            @PathVariable UUID seatId,
            @Valid @RequestBody UpdateSeatStatusRequest request) {
        SeatResponse response = sectionService.updateSeatStatus(venueId, sectionId, seatId, request);
        return ResponseEntity.ok(response);
    }
}
