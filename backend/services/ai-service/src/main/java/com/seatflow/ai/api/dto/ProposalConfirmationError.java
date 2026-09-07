package com.seatflow.ai.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Machine-readable confirmation failure carrying a canonical P15-005 code.
 */
@Schema(description = "Explicit-confirmation failure with a canonical stable code")
public record ProposalConfirmationError(

        @Schema(description = "Canonical failure code, e.g. STALE_PROPOSAL", example = "PRICE_CHANGED")
        String code,

        @Schema(description = "User-safe message (never a raw downstream body)", example = "The price changed. Please request fresh seats.")
        String message
) {}
