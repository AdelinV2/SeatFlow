package com.seatflow.user.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "User profile response")
public record UserProfileResponse(

    @Schema(description = "Internal user UUID", example = "123e4567-e89b-12d3-a456-426614174000")
    UUID id,

    @Schema(description = "User email address", example = "alex.smith@example.com")
    String email,

    @Schema(description = "User's phone number", example = "+1-555-0199")
    String phone,

    @Schema(description = "Account creation timestamp (ISO-8601 UTC)", example = "2026-08-23T10:00:00Z")
    Instant createdAt

) {}
