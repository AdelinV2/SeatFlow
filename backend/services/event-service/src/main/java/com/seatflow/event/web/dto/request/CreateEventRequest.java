package com.seatflow.event.web.dto.request;

import com.seatflow.event.model.enums.EventCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Request body for creating a new catalog event")
public record CreateEventRequest(

    @Schema(description = "Existing venue UUID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Venue ID is required")
    UUID venueId,

    @Schema(description = "Event title", example = "Hamlet", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Title is required")
    @Size(max = 255, message = "Title must not exceed 255 characters")
    String title,

    @Schema(description = "Full event description", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Description is required")
    String description,

    @Schema(description = "Catalog category", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Category is required")
    EventCategory category,

    @Schema(description = "Public banner image URL", example = "https://cdn.example.com/hamlet.png")
    @Size(max = 1000, message = "Banner URL must not exceed 1000 characters")
    @Pattern(regexp = "^https?://.+$", message = "bannerUrl must be an HTTP(S) URL")
    String bannerUrl,

    @Schema(description = "UTC start time of the event", example = "2027-05-01T19:30:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Event date is required")
    @Future(message = "Event date must be in the future")
    Instant eventDate

) {}
