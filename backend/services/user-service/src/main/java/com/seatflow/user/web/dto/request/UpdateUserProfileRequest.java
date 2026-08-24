package com.seatflow.user.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for updating user profile details")
public record UpdateUserProfileRequest(

    @Schema(description = "User's phone number", example = "+1-555-0199")
    @Size(max = 50, message = "Phone number must not exceed 50 characters")
    String phone

) {}
