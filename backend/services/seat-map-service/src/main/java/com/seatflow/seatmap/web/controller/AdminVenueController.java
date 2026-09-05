package com.seatflow.seatmap.web.controller;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.seatmap.service.VenueLayoutService;
import com.seatflow.seatmap.service.VenueSectionService;
import com.seatflow.seatmap.service.VenueService;
import com.seatflow.seatmap.web.dto.request.CreateVenueRequest;
import com.seatflow.seatmap.web.dto.request.CreateVenueSectionRequest;
import com.seatflow.seatmap.web.dto.request.SaveVenueLayoutRequest;
import com.seatflow.seatmap.web.dto.request.UpdateSeatStatusRequest;
import com.seatflow.seatmap.web.dto.request.UpdateVenueRequest;
import com.seatflow.seatmap.web.dto.response.SeatResponse;
import com.seatflow.seatmap.web.dto.response.VenueResponse;
import com.seatflow.seatmap.web.dto.response.VenueSeatMapLayoutResponse;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/venues")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Venues (Admin)", description = "Admin-only venue and section management APIs")
public class AdminVenueController {

    private final VenueService venueService;
    private final VenueSectionService sectionService;
    private final VenueLayoutService layoutService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
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
    @PreAuthorize("hasRole('ADMIN')")
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
    @PreAuthorize("hasRole('ADMIN')")
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
    @PreAuthorize("hasRole('ADMIN')")
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

    @DeleteMapping("/{venueId}/sections/{sectionId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Deactivate a venue section",
        description = "Soft-deactivates a section and all its seats (sets is_active=false) and increments layout_version. Rows are never hard-deleted."
    )
    @ApiResponse(responseCode = "204", description = "Section deactivated successfully")
    @ApiResponse(responseCode = "404", description = "Venue or section not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Admin role required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<Void> deleteSection(
            @PathVariable UUID venueId,
            @PathVariable UUID sectionId) {
        sectionService.deleteSection(venueId, sectionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{venueId}/layout")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Get complete editable venue layout",
        description = "Returns the complete editor layout including inactive sections/seats and all layout elements."
    )
    @ApiResponse(responseCode = "200", description = "Editable layout retrieved successfully",
        content = @Content(schema = @Schema(implementation = VenueSeatMapLayoutResponse.class)))
    @ApiResponse(responseCode = "404", description = "Venue not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Admin role required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<VenueSeatMapLayoutResponse> getEditableLayout(
            @PathVariable UUID venueId) {
        VenueSeatMapLayoutResponse response = layoutService.getEditableLayout(venueId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{venueId}/layout/validation")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Validate a venue layout snapshot without persisting",
        description = "Runs structural validation on the submitted snapshot. Returns 204 when valid; no write occurs."
    )
    @ApiResponse(responseCode = "204", description = "Layout snapshot is valid")
    @ApiResponse(responseCode = "400", description = "Validation error",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Venue not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Admin role required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<Void> validateLayout(
            @PathVariable UUID venueId,
            @Valid @RequestBody SaveVenueLayoutRequest request) {
        layoutService.validateLayout(venueId, request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{venueId}/layout")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Atomically save a complete venue layout snapshot",
        description = "Persists sections, seats and elements in one transaction guarded by layoutVersion. Stale versions return 409 SF_409_CONFLICT."
    )
    @ApiResponse(responseCode = "200", description = "Layout saved successfully",
        content = @Content(schema = @Schema(implementation = VenueSeatMapLayoutResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Venue not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Stale layout version",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Admin role required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<VenueSeatMapLayoutResponse> saveLayout(
            @PathVariable UUID venueId,
            @Valid @RequestBody SaveVenueLayoutRequest request) {
        VenueSeatMapLayoutResponse response = layoutService.saveLayout(venueId, request);
        return ResponseEntity.ok(response);
    }
}
