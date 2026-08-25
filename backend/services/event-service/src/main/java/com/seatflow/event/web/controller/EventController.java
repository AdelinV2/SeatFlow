package com.seatflow.event.web.controller;

import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.event.model.enums.EventCategory;
import com.seatflow.event.service.EventService;
import com.seatflow.event.web.dto.response.EventDetailResponse;
import com.seatflow.event.web.dto.response.EventSeatMapResponse;
import com.seatflow.event.web.dto.response.EventSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
@Tag(name = "Events (Public)", description = "Public published event catalog and priced seat maps")
public class EventController {

    private static final Set<String> ALLOWED_SORTS = Set.of("eventDate", "title", "createdAt");

    private final EventService eventService;

    @GetMapping
    @Operation(summary = "List published upcoming events",
            description = "Returns a paginated public catalog filtered by optional category and case-insensitive search.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Events retrieved",
                content = @Content(schema = @Schema(implementation = PagedResult.class))),
        @ApiResponse(responseCode = "400", description = "Invalid pagination or sort parameters",
                content = @Content(schema = @Schema(implementation = PagedResult.class)))
    })
    public ResponseEntity<PagedResult<EventSummaryResponse>> listEvents(
            @RequestParam(required = false) EventCategory category,
            @RequestParam(required = false) @Size(max = 100) String search,
            @PageableDefault(size = 20, sort = "eventDate", direction = Sort.Direction.ASC) Pageable pageable) {
        validatePageable(pageable);
        PagedResult<EventSummaryResponse> result = eventService.findPublishedEvents(category, search, pageable);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{eventId}")
    @Operation(summary = "Get published event details",
            description = "Returns published event metadata and its active pricing tiers.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Event retrieved",
                content = @Content(schema = @Schema(implementation = EventDetailResponse.class))),
        @ApiResponse(responseCode = "404", description = "Event not found",
                content = @Content(schema = @Schema(implementation = EventDetailResponse.class)))
    })
    public ResponseEntity<EventDetailResponse> getEvent(@PathVariable UUID eventId) {
        return ResponseEntity.ok(eventService.getPublishedEvent(eventId));
    }

    @GetMapping("/{eventId}/seat-map")
    @Operation(summary = "Get priced event seat map",
            description = "Combines the authoritative venue layout with this event's active section pricing tiers.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Priced seat map retrieved",
                content = @Content(schema = @Schema(implementation = EventSeatMapResponse.class))),
        @ApiResponse(responseCode = "404", description = "Event or venue not found",
                content = @Content(schema = @Schema(implementation = EventSeatMapResponse.class))),
        @ApiResponse(responseCode = "503", description = "Seat map service unavailable",
                content = @Content(schema = @Schema(implementation = EventSeatMapResponse.class)))
    })
    public ResponseEntity<EventSeatMapResponse> getSeatMap(@PathVariable UUID eventId) {
        return ResponseEntity.ok(eventService.getEventSeatMap(eventId));
    }

    private void validatePageable(Pageable pageable) {
        if (pageable.getPageNumber() < 0) {
            throw new ValidationException("Page index must be zero or greater", ErrorCode.INVALID_REQUEST);
        }
        if (pageable.getPageSize() < 1 || pageable.getPageSize() > 100) {
            throw new ValidationException("Page size must be between 1 and 100", ErrorCode.INVALID_REQUEST);
        }
        for (Sort.Order order : pageable.getSort()) {
            if (!ALLOWED_SORTS.contains(order.getProperty())) {
                throw new ValidationException(
                        "Sorting is only allowed on eventDate, title, or createdAt", ErrorCode.INVALID_REQUEST);
            }
        }
    }
}
