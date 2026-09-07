package com.seatflow.ai.api;

import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.context.AiRequestContextFactory;
import com.seatflow.ai.orchestration.AssistantOrchestrator;
import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Explicit conversation reset endpoint (TASK-P15-004 section 3.2).
 *
 * <p>Only the owner may reset a conversation; reset deletes its chat memory and orchestration
 * metadata (and, after P15-005, supersedes/deletes any unconfirmed active proposal for that
 * conversation). Returns {@code 204} on success. Reset never cancels a real Reservation Service
 * hold that already exists (this task creates no holds).
 */
@RestController
@RequestMapping("/api/ai/conversations")
@RequiredArgsConstructor
@Tag(name = "AI Assistant", description = "Conversation reset (owner-safe)")
public class ConversationController {

    private final AssistantOrchestrator orchestrator;
    private final AiRequestContextFactory requestContexts;

    @DeleteMapping("/{conversationId}")
    @Operation(summary = "Reset an owned conversation",
            description = "Deletes chat memory and orchestration metadata for the caller's own conversation. "
                    + "Never cancels a real reservation hold.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Conversation reset"),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Unknown conversation (or cross-owner ID)",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Conversation busy processing a turn; retry shortly",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<Void> reset(@PathVariable UUID conversationId) {
        AiRequestContext context = requestContexts.requireAuthenticated();
        boolean cleared = orchestrator.reset(conversationId, context.userId());
        if (!cleared) {
            throw new ResourceNotFoundException("Conversation not found: " + conversationId);
        }
        return ResponseEntity.noContent().build();
    }
}
