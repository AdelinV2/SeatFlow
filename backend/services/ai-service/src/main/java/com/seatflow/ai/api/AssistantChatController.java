package com.seatflow.ai.api;

import com.seatflow.ai.api.dto.AssistantChatRequest;
import com.seatflow.ai.api.dto.AssistantChatResponse;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.context.AiRequestContextFactory;
import com.seatflow.ai.orchestration.AssistantOrchestrator;
import com.seatflow.common.domain.dto.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conversational orchestration endpoint (TASK-P15-004 section 3.1).
 *
 * <p>HTTP adapter only: validates the typed bounded contract, resolves the server-side caller
 * context (never trusts model/client identity), and delegates to {@link AssistantOrchestrator}.
 * No reservation is created by this endpoint.
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "AI Assistant", description = "Conversational orchestration with controlled read-only tools")
public class AssistantChatController {

    private final AssistantOrchestrator orchestrator;
    private final AiRequestContextFactory requestContexts;

    @PostMapping("/chat")
    @Operation(summary = "Chat with the SeatFlow assistant",
            description = "Accepts a bounded user message, maintains owner-bound conversational context, "
                    + "lets the model invoke only the five P15-004 read-only tools, and returns authoritative "
                    + "cards. Stops at PROPOSAL_READY/CONFIRMATION_REQUIRED; never creates a reservation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Chat turn completed (including recoverable AI errors)",
                    content = @Content(schema = @Schema(implementation = AssistantChatResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error (message must be 1..2000 after trim)",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Unknown conversation (or cross-owner ID)",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Conversation busy processing a previous turn",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<AssistantChatResponse> chat(
            @Valid @RequestBody AssistantChatRequest request) {
        AiRequestContext context = requestContexts.requireAuthenticated();
        AssistantChatResponse response = orchestrator.chat(
                request.conversationId(), request.message(), context.userId(), context);
        return ResponseEntity.ok(response);
    }
}
