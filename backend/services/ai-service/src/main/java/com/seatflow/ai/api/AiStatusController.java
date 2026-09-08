package com.seatflow.ai.api;

import com.seatflow.ai.api.dto.AiFeatureStatusResponse;
import com.seatflow.ai.service.AiStatusService;
import com.seatflow.common.domain.dto.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "AI Assistant", description = "AI feature availability (Groq via Spring AI; no domain tools in P15-001)")
public class AiStatusController {

    private final AiStatusService statusService;

    @GetMapping("/status")
    @Operation(summary = "Get AI feature status",
            description = "Returns only feature/provider availability state, never secret/config values.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status retrieved",
                    content = @Content(schema = @Schema(implementation = AiFeatureStatusResponse.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<AiFeatureStatusResponse> getStatus() {
        return ResponseEntity.ok(new AiFeatureStatusResponse(
                statusService.isEnabled(),
                statusService.currentState(),
                statusService.configuredModel()));
    }
}
