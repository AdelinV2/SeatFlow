package com.seatflow.ai.orchestration;

import com.seatflow.ai.tool.dto.AvailableSeatsResult;
import com.seatflow.ai.tool.dto.EventSessionsToolResult;
import com.seatflow.ai.tool.dto.EventToolResult;
import com.seatflow.ai.tool.dto.FindBestSeatsResult;
import com.seatflow.ai.tool.dto.SearchEventsResult;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Seam between the orchestrator and the model provider (Groq via Spring AI in production, mocks in
 * CI/tests).
 *
 * <p>The request contains only sanitized data: the versioned system prompt, bounded history texts,
 * the trimmed user message, and compact tool DTO summaries. It never contains the
 * {@code GROQ_API_KEY}, Authorization/Bearer JWTs, refresh tokens, Stripe/payment secrets, DB
 * credentials, stack traces, unrelated PII, or entire upstream payloads. No lock key or owner
 * metadata is sent.
 *
 * <p>The result carries presentation prose plus the structured tool outputs the application used to
 * derive state. Chain-of-thought/reasoning is never included.
 */
public interface AssistantModelClient {

    ModelTurnResult execute(ModelTurnRequest request);

    /**
     * Sanitized provider request. {@code history} holds prior-turn messages (oldest first, already
     * bounded by chat memory, roles preserved); the current user message travels separately in
     * {@code userMessage}. The client must pass history explicitly and must NOT attach a persisting
     * memory advisor: the orchestrator owns all memory writes (one user/assistant pair per
     * successful turn, nothing on provider failure), so turns store exactly 2 messages and mocked
     * tests reproduce production counts.
     */
    record ModelTurnRequest(
            String systemPrompt,
            List<Message> history,
            String userMessage,
            List<String> allowedToolNames,
            String conversationId) {
        public ModelTurnRequest {
            history = history == null ? List.of() : List.copyOf(history);
            allowedToolNames = allowedToolNames == null ? List.of() : List.copyOf(allowedToolNames);
        }
    }

    /**
     * Structured model turn. Tool results are the authoritative DTOs returned by application tool
     * execution (not parsed prose). {@code assistantMessage} is presentation only.
     */
    record ModelTurnResult(
            String assistantMessage,
            SearchEventsResult searchEvents,
            EventToolResult event,
            EventSessionsToolResult sessions,
            AvailableSeatsResult availableSeats,
            FindBestSeatsResult bestSeats) {
    }
}
