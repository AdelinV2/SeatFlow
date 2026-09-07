package com.seatflow.ai.api.dto;

import com.seatflow.ai.service.AiFeatureState;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Safe AI feature/provider availability state; never contains secrets")
public record AiFeatureStatusResponse(
        @Schema(description = "Whether the AI feature flag is enabled", example = "true")
        boolean enabled,

        @Schema(description = "Stable feature state", example = "READY")
        AiFeatureState state,

        @Schema(description = "Configured non-secret model ID", example = "openai/gpt-oss-20b")
        String model
) {
}
