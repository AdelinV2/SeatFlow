package com.seatflow.user.web.controller;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.user.service.UserService;
import com.seatflow.user.web.dto.response.UserProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Validated
@Tag(name = "Admin - User Management", description = "Admin-only user management and listing APIs")
public class AdminUserController {

    private final UserService userService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "List all registered users (Admin)",
        description = "Returns a paginated list of all registered user profiles. Requires ROLE_ADMIN."
    )
    @ApiResponse(responseCode = "200", description = "Paginated user list retrieved successfully")
    @ApiResponse(responseCode = "401", description = "Authentication required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Admin role required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<PagedResult<UserProfileResponse>> getAllUsers(
            @Parameter(description = "Page number (0-indexed)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size", example = "20")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Sort field (createdAt, email, externalId, phone, updatedAt)", example = "createdAt")
            @Pattern(regexp = "createdAt|email|externalId|phone|updatedAt", flags = Pattern.Flag.CASE_INSENSITIVE,
                    message = "sort must be one of: createdAt, email, externalId, phone, updatedAt")
            @RequestParam(defaultValue = "createdAt") String sort,
            @Parameter(description = "Sort direction (asc/desc)", example = "desc")
            @Pattern(regexp = "asc|desc", flags = Pattern.Flag.CASE_INSENSITIVE,
                    message = "direction must be 'asc' or 'desc'")
            @RequestParam(defaultValue = "desc") String direction) {

        Sort.Direction sortDirection = Sort.Direction.fromString(direction);
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDirection, sort));

        PagedResult<UserProfileResponse> result = userService.getAllUsers(pageable);
        return ResponseEntity.ok(result);
    }
}
