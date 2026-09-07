package com.seatflow.ai.orchestration;

import com.seatflow.ai.api.dto.AssistantCard;
import com.seatflow.ai.api.dto.AssistantChatErrorCode;
import com.seatflow.ai.api.dto.AssistantChatResponse;
import com.seatflow.ai.api.dto.AssistantError;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.proposal.ReservationProposal;
import com.seatflow.ai.service.AiStatusService;
import com.seatflow.ai.service.ProposalService;
import com.seatflow.ai.tool.dto.AvailableSeatsResult;
import com.seatflow.ai.tool.dto.EventSessionsToolResult;
import com.seatflow.ai.tool.dto.EventToolResult;
import com.seatflow.ai.tool.dto.FindBestSeatsResult;
import com.seatflow.ai.tool.dto.SearchEventsResult;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Conversational orchestration around Groq + Spring AI read-only tools (TASK-P15-004,
 * TASK-P15-005).
 *
 * <p>Accepts a user message, maintains bounded user-owned conversational context, lets the model
 * invoke only approved read-only tools, and returns a structured application response.
 * Stops at {@code PROPOSAL_READY / CONFIRMATION_REQUIRED}; the {@code findBestSeats} turn also
 * stores one exact server-side secure proposal for the explicit
 * {@code POST /api/ai/proposals/{proposalId}/confirm} boundary. Chat text such as {@code yes}
 * and model tool calls can never create a reservation — only the dedicated confirmation action
 * authorizes the single Reservation Service write.
 *
 * <p>Security highlights: server generates UUIDs; owner is the authenticated subject; cross-owner
 * access yields {@code 404} (anti-enumeration, never context); concurrent turns are serialized with
 * a bounded single-flight lock ({@code 409} busy); message validated to {@code 1..2000} after trim
 * before any provider call; provider request never contains JWT/API key/secrets; tool results are
 * serialized as data, not privileged instructions; proposal drafts come only from authoritative
 * {@code findBestSeats} DTOs, never prose parsing; chain-of-thought never exposed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantOrchestrator {

    static final int MESSAGE_MAX_LENGTH = 2000;
    static final long CONVERSATION_LOCK_TIMEOUT_MS = 5000L;

    private final ConversationStore conversations;
    private final ChatMemory chatMemory;
    private final AssistantPromptFactory promptFactory;
    private final AssistantToolRegistry toolRegistry;
    private final AssistantCardAssembler cards;
    private final AssistantModelClient modelClient;
    private final AssistantToolObservation observation;
    private final AssistantProviderErrorMapper errorMapper;
    private final AiStatusService statusService;
    private final ProposalService proposalService;
    private final Clock clock;

    /**
     * Executes one chat turn.
     *
     * @param conversationId existing ID or {@code null} on first turn (server generates)
     * @param rawMessage user message (validated after trim, {@code 1..2000})
     * @param ownerSubject authenticated subject (conversation owner)
     * @param toolContext server-side tool context (bearer never sent to the model)
     */
    public AssistantChatResponse chat(UUID conversationId, String rawMessage, String ownerSubject,
                                      AiRequestContext toolContext) {
        String userMessage = normalizeMessage(rawMessage);
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new ValidationException("Authentication is required", ErrorCode.UNAUTHORIZED);
        }

        ConversationStore.ConversationRecord record;
        boolean isFirstTurn = conversationId == null;
        if (isFirstTurn) {
            record = conversations.create(ownerSubject);
            conversationId = record.conversationId();
        } else {
            ConversationStore.ConversationRecord peeked = conversations.peek(conversationId);
            if (peeked != null && conversations.isExpiredAt(peeked, clock.instant())) {
                conversations.remove(conversationId);
                chatMemory.clear(conversationId.toString());
                log.info("AI conversation expired on turn: conversationId={}", conversationId);
                return expiredResponse(conversationId);
            }
            record = conversations.getForOwner(conversationId, ownerSubject);
            if (record == null) {
                throw new ResourceNotFoundException("Conversation not found: " + conversationId);
            }
        }

        boolean locked;
        try {
            locked = conversations.tryLock(conversationId, CONVERSATION_LOCK_TIMEOUT_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new com.seatflow.common.domain.exception.ConflictException(
                    "Conversation is busy. Please retry shortly.", ErrorCode.CONFLICT);
        }
        if (!locked) {
            log.warn("AI conversation busy, rejecting parallel turn: conversationId={}", conversationId);
            throw new com.seatflow.common.domain.exception.ConflictException(
                    "Conversation is busy processing a previous turn. Please retry shortly.",
                    ErrorCode.CONFLICT);
        }
        try {
            // Re-check expiry under lock (TOCTOU safe).
            ConversationStore.ConversationRecord current =
                    conversations.getForOwner(conversationId, ownerSubject);
            if (current == null) {
                ConversationStore.ConversationRecord peeked = conversations.peek(conversationId);
                if (peeked != null) {
                    conversations.remove(conversationId);
                    chatMemory.clear(conversationId.toString());
                    return expiredResponse(conversationId);
                }
                throw new ResourceNotFoundException("Conversation not found: " + conversationId);
            }

            if (!statusService.isChatAvailable()) {
                return providerErrorResponse(conversationId, current,
                        statusService.isEnabled()
                                ? AssistantChatErrorCode.AI_MISCONFIGURED
                                : AssistantChatErrorCode.AI_DISABLED,
                        statusService.isEnabled()
                                ? "AI provider credentials are invalid. AI is temporarily unavailable."
                                : "AI assistant is disabled. Core booking remains available.");
            }

            // Single memory-write ownership: history is read BEFORE the model call (prior turns
            // only); exactly one user/assistant pair is appended AFTER a successful turn. Failed
            // turns (provider error or invalid tool data) store nothing, so a retry starts clean
            // and the 24-message bound always holds whole turns (2 messages per turn).
            List<Message> history = readHistoryMessages(conversationId);
            AssistantModelClient.ModelTurnRequest modelRequest =
                    new AssistantModelClient.ModelTurnRequest(
                            promptFactory.systemPrompt(), history, userMessage,
                            List.copyOf(AssistantToolRegistry.ORDINARY_CHAT_TOOLS),
                            conversationId.toString());

            AssistantModelClient.ModelTurnResult modelResult;
            AssistantToolObservation.ObservedTurn observedTurn = null;
            observation.bind(conversationId);
            try {
                modelResult = modelClient.execute(modelRequest);
            } catch (AssistantProviderException ex) {
                return providerErrorResponse(conversationId, current, ex.getErrorCode(),
                        ex.getMessage());
            } finally {
                observedTurn = observation.current();
                observation.unbind();
            }

            // Prefer explicit mocked DTOs in tests; fall back to observed production tool DTOs.
            SearchEventsResult searchEvents = modelResult != null ? modelResult.searchEvents() : null;
            EventToolResult event = modelResult != null ? modelResult.event() : null;
            EventSessionsToolResult sessions = modelResult != null ? modelResult.sessions() : null;
            AvailableSeatsResult availableSeats =
                    modelResult != null ? modelResult.availableSeats() : null;
            FindBestSeatsResult bestSeats = modelResult != null ? modelResult.bestSeats() : null;
            AssistantToolObservation.FindBestSeatsRequestSnapshot bestSeatsFingerprint = null;
            if (observedTurn != null) {
                if (searchEvents == null) {
                    searchEvents = observedTurn.searchEvents;
                }
                if (event == null) {
                    event = observedTurn.event;
                }
                if (sessions == null) {
                    sessions = observedTurn.sessions;
                }
                if (availableSeats == null) {
                    availableSeats = observedTurn.availableSeats;
                }
                if (bestSeats == null) {
                    bestSeats = observedTurn.bestSeats;
                    bestSeatsFingerprint = observedTurn.bestSeatsRequest;
                }
            }
            String assistantMessage = modelResult == null ? null : modelResult.assistantMessage();
            assistantMessage = errorMapper.sanitizeAssistantMessage(assistantMessage);

            // Derive application state from tools, never free-form text.
            List<AssistantCard> responseCards = new ArrayList<>();
            List<String> suggestedActions = new ArrayList<>();
            AssistantState nextState = AssistantState.IDLE;

            if (searchEvents != null && searchEvents.events() != null && !searchEvents.events().isEmpty()) {
                responseCards.addAll(cards.eventCards(searchEvents));
                nextState = AssistantState.DISCOVERING;
                suggestedActions.add("Show sessions for an event");
            }
            if (event != null) {
                responseCards.add(cards.eventCard(event));
                nextState = AssistantState.DISCOVERING;
            }
            if (sessions != null && sessions.sessions() != null && !sessions.sessions().isEmpty()) {
                responseCards.addAll(cards.sessionCards(sessions));
                nextState = AssistantState.DISCOVERING;
                suggestedActions.add("Check available seats for a session");
            }
            if (availableSeats != null && availableSeats.seats() != null
                    && !availableSeats.seats().isEmpty()) {
                nextState = AssistantState.DISCOVERING;
                suggestedActions.add("Ask for best seats with quantity and budget");
            }

            if (bestSeats != null && "OK".equals(bestSeats.status())
                    && bestSeats.candidates() != null && !bestSeats.candidates().isEmpty()) {
                FindBestSeatsResult.SeatCandidate best = bestSeats.candidates().getFirst();
                try {
                    validateBestSeatsCandidate(best);
                } catch (AssistantProviderException ex) {
                    return providerErrorResponse(conversationId, current, ex.getErrorCode(),
                            ex.getMessage());
                }
                String fingerprint = bestSeatsFingerprint != null
                        ? fingerprintOf(bestSeatsFingerprint)
                        : fingerprintOf(bestSeats);
                ConversationStore.ConversationRecord latest =
                        conversations.getForOwner(conversationId, ownerSubject);
                ProposalDraft existing = latest == null ? null : latest.draft();
                ProposalDraft draft;
                if (existing != null && fingerprint.equals(existing.constraintsFingerprint())) {
                    // Same constraints: reuse the draft only when its secure proposal is still
                    // ACTIVE and owner-bound. An expired/consumed/superseded (or restart-lost)
                    // secure proposal must not be re-presented as confirmable — mint a fresh one
                    // so the card always carries a live confirmation ID.
                    ReservationProposal liveSecure =
                            proposalService.peekForOwner(existing.draftId(), ownerSubject);
                    if (liveSecure != null && liveSecure.status()
                            == com.seatflow.ai.proposal.ProposalStatus.ACTIVE) {
                        draft = existing;
                    } else {
                        ReservationProposal secure = createSecureProposal(
                                conversationId, ownerSubject, bestSeats, best, bestSeatsFingerprint);
                        if (secure == null) {
                            return providerErrorResponse(conversationId, current,
                                    AssistantChatErrorCode.AI_RESPONSE_INVALID,
                                    "Could not create a proposal. Please try again.");
                        }
                        draft = buildDraftWithId(bestSeats, best, fingerprint, secure.proposalId());
                        conversations.putDraft(conversationId, draft);
                    }
                } else {
                    // Secure server proposal first (explicit-confirmation boundary): the card
                    // carries the secure proposal ID so only the dedicated confirm endpoint can
                    // authorize the single Reservation Service write. Draft and secure proposal
                    // share the ID so reset/supersede stays coherent.
                    ReservationProposal secure = createSecureProposal(
                            conversationId, ownerSubject, bestSeats, best, bestSeatsFingerprint);
                    if (secure == null) {
                        return providerErrorResponse(conversationId, current,
                                AssistantChatErrorCode.AI_RESPONSE_INVALID,
                                "Could not create a proposal. Please try again.");
                    }
                    draft = buildDraftWithId(bestSeats, best, fingerprint, secure.proposalId());
                    conversations.putDraft(conversationId, draft);
                }
                responseCards.add(cards.seatSetCard(best, bestSeats.eventSessionId()));
                responseCards.add(cards.proposalCard(draft));
                nextState = AssistantState.CONFIRMATION_REQUIRED;
                suggestedActions.clear();
                suggestedActions.add("Confirm the exact proposal to continue");
                suggestedActions.add("Change quantity, budget, or section to update the proposal");
                assistantMessage = assistantMessage.isBlank()
                        ? "I found seats that match your request. Please confirm the exact proposal to continue. "
                          + "This proposal is not a hold."
                        : assistantMessage;
            } else if (bestSeats != null) {
                // Any best-seats outcome without a usable proposal (NO_MATCH, PRICING_SELECTION_REQUIRED,
                // or OK with an empty candidate list such as a sold-out session) guides the user to
                // adjust constraints instead of falling through to the generic IDLE fallback.
                nextState = AssistantState.DISCOVERING;
                if ("PRICING_SELECTION_REQUIRED".equals(bestSeats.status())) {
                    responseCards.add(cards.infoCard("Pricing selection required",
                            "Several pricing categories are available. Please choose a category to continue."));
                } else {
                    responseCards.add(cards.infoCard("No matching seats",
                            "No seats match the current constraints. Try a different quantity, budget, or section."));
                }
            }

            if (responseCards.isEmpty() && nextState == AssistantState.IDLE) {
                responseCards.add(cards.infoCard("How I can help",
                        "Tell me the event, session, and how many seats you need (1..10). "
                        + "I will search authoritative availability and propose exact seats for confirmation."));
                suggestedActions.add("Search events by title or category");
            }

            if (nextState == AssistantState.PROPOSAL_READY) {
                nextState = AssistantState.CONFIRMATION_REQUIRED;
            }

            chatMemory.add(conversationId.toString(),
                    List.of(new UserMessage(userMessage), new AssistantMessage(assistantMessage)));
            conversations.touchWithState(conversationId, nextState);

            log.info("AI chat turn completed: conversationId={}, state={}, cards={}",
                    conversationId, nextState, responseCards.size());
            return new AssistantChatResponse(conversationId, assistantMessage, nextState,
                    responseCards, suggestedActions, null);
        } finally {
            conversations.unlock(conversationId);
        }
    }

    /**
     * Explicit owner-safe reset ({@code DELETE /api/ai/conversations/{id}}).
     *
     * <p>Busy/interrupted resets fail with {@code 409} (same single-flight semantics as chat turns)
     * instead of a false {@code 204}: nothing is partially cleared, and an in-flight turn cannot be
     * raced by a concurrent reset. Reset supersedes the conversation's active secure proposal
     * (P15-005) but never cancels an already-created Reservation Service hold.
     *
     * @return {@code true} when an owned conversation was cleared; {@code false} when unknown
     *     (callers answer {@code 404} without leaking cross-owner existence).
     */
    public boolean reset(UUID conversationId, String ownerSubject) {
        if (conversationId == null || ownerSubject == null || ownerSubject.isBlank()) {
            return false;
        }
        ConversationStore.ConversationRecord record =
                conversations.getForOwner(conversationId, ownerSubject);
        if (record == null) {
            return false;
        }
        boolean locked;
        try {
            locked = conversations.tryLock(conversationId, CONVERSATION_LOCK_TIMEOUT_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new com.seatflow.common.domain.exception.ConflictException(
                    "Conversation is busy. Please retry the reset shortly.", ErrorCode.CONFLICT);
        }
        if (!locked) {
            log.warn("AI conversation reset rejected, turn in flight: conversationId={}",
                    conversationId);
            throw new com.seatflow.common.domain.exception.ConflictException(
                    "Conversation is busy processing a turn. Please retry the reset shortly.",
                    ErrorCode.CONFLICT);
        }
        try {
            conversations.clearDraft(conversationId);
            proposalService.supersedeForConversation(conversationId, ownerSubject);
            chatMemory.clear(conversationId.toString());
            conversations.remove(conversationId);
            log.info("AI conversation reset by owner: conversationId={}", conversationId);
            return true;
        } finally {
            conversations.unlock(conversationId);
        }
    }

    String normalizeMessage(String raw) {
        if (raw == null) {
            throw new ValidationException("Message is required", ErrorCode.INVALID_REQUEST);
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new ValidationException("Message must not be blank", ErrorCode.INVALID_REQUEST);
        }
        if (trimmed.length() > MESSAGE_MAX_LENGTH) {
            throw new ValidationException(
                    "Message must be at most " + MESSAGE_MAX_LENGTH + " characters after trim",
                    ErrorCode.INVALID_REQUEST);
        }
        return trimmed;
    }

    private List<Message> readHistoryMessages(UUID conversationId) {
        try {
            List<Message> messages = chatMemory.get(conversationId.toString());
            if (messages == null) {
                return List.of();
            }
            // Memory holds only orchestrator-written user/assistant texts (bounded, sanitized);
            // blank entries are dropped so the model never receives empty turns.
            return messages.stream()
                    .filter(message -> message.getText() != null && !message.getText().isBlank())
                    .toList();
        } catch (Exception ex) {
            log.warn("AI history read failed, continuing without history: conversationId={}",
                    conversationId);
            return List.of();
        }
    }

    private AssistantChatResponse providerErrorResponse(UUID conversationId,
                                                        ConversationStore.ConversationRecord record,
                                                        AssistantChatErrorCode code, String message) {
        conversations.touchWithState(conversationId, AssistantState.ERROR_RECOVERABLE);
        List<String> actions = List.of("Try again shortly", "Continue browsing events without AI");
        return new AssistantChatResponse(conversationId,
                "The assistant is temporarily unavailable. Core booking remains available.",
                AssistantState.ERROR_RECOVERABLE, List.of(), actions,
                new AssistantError(code, message));
    }

    private AssistantChatResponse expiredResponse(UUID conversationId) {
        return new AssistantChatResponse(conversationId,
                "This conversation expired. Please start a new conversation.",
                AssistantState.EXPIRED, List.of(), List.of("Start a new conversation"), null);
    }

    void validateBestSeatsCandidate(FindBestSeatsResult.SeatCandidate candidate) {
        if (candidate.seatIds() == null || candidate.seatIds().isEmpty()
                || candidate.seatIds().size() > 10) {
            throw new AssistantProviderException(AssistantChatErrorCode.AI_RESPONSE_INVALID,
                    "Invalid seat proposal received. Please try again.");
        }
        if (candidate.totalPriceMinor() < 0) {
            throw new AssistantProviderException(AssistantChatErrorCode.AI_RESPONSE_INVALID,
                    "Invalid seat proposal received. Please try again.");
        }
        if (candidate.currency() == null || !candidate.currency().matches("[A-Z]{3}")) {
            throw new AssistantProviderException(AssistantChatErrorCode.AI_RESPONSE_INVALID,
                    "Invalid seat proposal received. Please try again.");
        }
    }

    ProposalDraft buildDraft(FindBestSeatsResult result, FindBestSeatsResult.SeatCandidate best,
                              String fingerprint) {
        return buildDraftWithId(result, best, fingerprint, UUID.randomUUID());
    }

    ProposalDraft buildDraftWithId(FindBestSeatsResult result, FindBestSeatsResult.SeatCandidate best,
                                    String fingerprint, UUID draftId) {
        List<String> labels = best.seats() == null ? List.of() : best.seats().stream()
                .map(seat -> {
                    String row = seat.rowLabel() == null ? "?" : seat.rowLabel();
                    String section = seat.sectionName() == null ? "" : seat.sectionName() + " ";
                    return (section + "Row " + row + " Seat " + seat.seatNumber()).trim();
                })
                .toList();
        String sectionSummary = "Selected seats";
        if (best.seats() != null && !best.seats().isEmpty()) {
            String sections = best.seats().stream()
                    .map(seat -> seat.sectionName() == null ? "?" : seat.sectionName())
                    .distinct()
                    .sorted()
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("?");
            sectionSummary = "Section(s) " + sections;
        }
        return new ProposalDraft(draftId, null, result.eventSessionId(),
                List.copyOf(best.seatIds()), labels, sectionSummary, best.totalPriceMinor(),
                best.currency(), best.contiguous(),
                best.reasons() == null ? List.of() : List.copyOf(best.reasons()),
                best.seatIds().size(), fingerprint, Instant.now(clock));
    }

    /**
     * Creates the authoritative secure proposal for the explicit-confirmation boundary from the
     * validated best-seats candidate. Returns {@code null} when creation fails so the caller can
     * answer a recoverable error instead of presenting an unconfirmable card.
     */
    ReservationProposal createSecureProposal(
            UUID conversationId,
            String ownerSubject,
            FindBestSeatsResult result,
            FindBestSeatsResult.SeatCandidate best,
            AssistantToolObservation.FindBestSeatsRequestSnapshot snapshot) {
        try {
            UUID eventId = parseOptionalUuid(snapshot == null ? null : snapshot.eventId());
            Long budget = snapshot == null ? null : snapshot.maxTotalPriceMinor();
            String category = snapshot == null ? null : snapshot.preferredCategory();
            String strategy = snapshot == null ? null : snapshot.strategy();
            List<ReservationProposal.SeatDisplay> displays =
                    best.seats() == null ? List.of() : best.seats().stream()
                            .filter(seat -> seat != null && seat.seatId() != null)
                            .map(seat -> new ReservationProposal.SeatDisplay(
                                    seat.seatId(),
                                    seatLabel(seat.sectionName(), seat.rowLabel(), seat.seatNumber()),
                                    seat.sectionName(), seat.rowLabel(), seat.seatNumber()))
                            .toList();
            List<UUID> tierIds = best.seats() == null ? List.of() : best.seats().stream()
                    .filter(seat -> seat != null)
                    .map(seat -> seat.pricingTierId())
                    .toList();
            return proposalService.createSecureProposal(
                    conversationId, ownerSubject, eventId, result.eventSessionId(),
                    List.copyOf(best.seatIds()), displays, tierIds, budget, category, strategy,
                    best.totalPriceMinor(), best.currency());
        } catch (RuntimeException ex) {
            log.warn("AI secure proposal creation failed: conversationId={}", conversationId, ex);
            return null;
        }
    }

    private String seatLabel(String sectionName, String rowLabel, int seatNumber) {
        String row = rowLabel == null ? "?" : rowLabel;
        String section = sectionName == null ? "" : sectionName + " ";
        return (section + "Row " + row + " Seat " + seatNumber).trim();
    }

    private UUID parseOptionalUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    String fingerprintOf(AssistantToolObservation.FindBestSeatsRequestSnapshot snapshot) {
        return String.join("|",
                nullToEmpty(snapshot.eventSessionId()),
                nullToEmpty(snapshot.eventId()),
                String.valueOf(snapshot.quantity()),
                String.valueOf(snapshot.maxTotalPriceMinor()),
                nullToEmpty(snapshot.currency()),
                nullToEmpty(snapshot.preferredSectionId()),
                nullToEmpty(snapshot.preferredSectionName()),
                nullToEmpty(snapshot.preferredCategory()),
                nullToEmpty(snapshot.strategy()));
    }

    String fingerprintOf(FindBestSeatsResult result) {
        if (result.candidates() == null || result.candidates().isEmpty()) {
            return result.eventSessionId() + "|empty";
        }
        FindBestSeatsResult.SeatCandidate best = result.candidates().getFirst();
        List<String> ids = best.seatIds().stream().map(Object::toString).sorted().toList();
        return result.eventSessionId() + "|" + best.seatIds().size() + "|"
                + best.totalPriceMinor() + "|" + best.currency() + "|" + String.join(",", ids);
    }

    private String nullToEmpty(Object value) {
        return value == null ? "" : value.toString();
    }
}
