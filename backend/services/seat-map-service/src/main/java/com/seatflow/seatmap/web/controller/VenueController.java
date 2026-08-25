package com.seatflow.seatmap.web.controller;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.seatmap.service.SeatMapLayoutService;
import com.seatflow.seatmap.service.VenueService;
import com.seatflow.seatmap.web.dto.response.VenueDetailResponse;
import com.seatflow.seatmap.web.dto.response.VenueResponse;
import com.seatflow.seatmap.web.dto.response.VenueSeatMapLayoutResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/venues")
@RequiredArgsConstructor
@Tag(name = "Venues (Public)", description = "Public venue browsing and seat map layout APIs")
public class VenueController {

    private final VenueService venueService;
    private final SeatMapLayoutService seatMapLayoutService;

    @GetMapping
    @Operation(
        summary = "List all venues",
        description = "Returns a paginated list of all venues. Supports optional filtering by city and name search."
    )
    @ApiResponse(responseCode = "200", description = "Venues retrieved successfully")
    public ResponseEntity<PagedResult<VenueResponse>> listVenues(
            @Parameter(description = "Filter by city") @RequestParam(required = false) String city,
            @Parameter(description = "Search by name (partial, case-insensitive)") @RequestParam(required = false) String name,
            @PageableDefault(size = 20) Pageable pageable) {
        PagedResult<VenueResponse> result = venueService.listVenues(city, name, pageable);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{venueId}")
    @Operation(
        summary = "Get venue details",
        description = "Returns detailed venue information including sections and active seat counts."
    )
    @ApiResponse(responseCode = "200", description = "Venue retrieved successfully",
        content = @Content(schema = @Schema(implementation = VenueDetailResponse.class)))
    @ApiResponse(responseCode = "404", description = "Venue not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<VenueDetailResponse> getVenueById(@PathVariable UUID venueId) {
        VenueDetailResponse response = venueService.getVenueById(venueId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{venueId}/layout")
    @Operation(
        summary = "Get venue seat map layout",
        description = "Returns the complete venue seat map including all sections and their active seats with grid coordinates. Used by the interactive seat map UI."
    )
    @ApiResponse(responseCode = "200", description = "Venue layout retrieved successfully",
        content = @Content(schema = @Schema(implementation = VenueSeatMapLayoutResponse.class)))
    @ApiResponse(responseCode = "404", description = "Venue not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<VenueSeatMapLayoutResponse> getVenueLayout(@PathVariable UUID venueId) {
        VenueSeatMapLayoutResponse response = seatMapLayoutService.getVenueLayout(venueId);
        return ResponseEntity.ok(response);
    }
}
