package com.seatflow.event.web.dto.request;

import com.seatflow.event.model.enums.EventCategory;
import com.seatflow.event.model.enums.EventStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

@Schema(description = "Request body for partially updating an existing event. Only provided (non-null) fields are changed.")
public record UpdateEventRequest(

    @Schema(description = "Event title", example = "Hamlet — Director's Cut")
    @Size(min = 1, max = 255, message = "Title must be between 1 and 255 characters")
    String title,

    @Schema(description = "Full event description")
    @Size(min = 1, message = "Description must not be blank")
    String description,

    @Schema(description = "Catalog category")
    EventCategory category,

    @Schema(description = "Public banner image URL", example = "https://cdn.example.com/hamlet-2.png")
    @Size(max = 1000, message = "Banner URL must not exceed 1000 characters")
    @Pattern(regexp = "^https?://.+$", message = "bannerUrl must be an HTTP(S) URL")
    String bannerUrl,

    @Schema(description = "UTC start time of the event", example = "2027-06-01T19:30:00Z")
    @Future(message = "Event date must be in the future")
    Instant eventDate,

    @Schema(description = "Lifecycle status")
    EventStatus status

) {}
