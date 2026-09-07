package com.seatflow.ai.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.ai.api.dto.AssistantCard;
import com.seatflow.ai.api.dto.AssistantChatResponse;
import com.seatflow.ai.tool.dto.AvailableSeatItem;
import com.seatflow.ai.tool.dto.EventSessionsToolResult;
import com.seatflow.ai.tool.dto.EventToolResult;
import com.seatflow.ai.tool.dto.FindBestSeatsResult;
import com.seatflow.ai.tool.dto.SearchEventsResult;
import com.seatflow.ai.tool.dto.SessionToolItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Card assembler tests: cards come from tool/application data (TASK-P15-004 section 7; mandatory 10, 15).
 */
class AssistantCardAssemblerTest {

    private final AssistantCardAssembler assembler = new AssistantCardAssembler();

    @Test
    @DisplayName("proposal card is visibly not a hold and carries exact tool snapshot")
    void proposalCardNotAHold() {
        UUID sessionId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        var draft = new ProposalDraft(UUID.randomUUID(), UUID.randomUUID(), sessionId,
                List.of(seatId), List.of("Stalls Row A Seat 1"), "Section(s) Stalls",
                1500L, "EUR", true, List.of("contiguous"), 1, "fp", Instant.now());

        AssistantCard card = assembler.proposalCard(draft);

        assertThat(card.type().name()).isEqualTo("RESERVATION_PROPOSAL");
        assertThat(card.seatIds()).containsExactly(seatId);
        assertThat(card.totalPriceMinor()).isEqualTo(1500L);
        assertThat(card.currency()).isEqualTo("EUR");
        assertThat(card.requiresExplicitConfirmation()).isTrue();
        assertThat(card.seatsHeld()).isFalse();
    }

    @Test
    @DisplayName("seat-set card mirrors candidate IDs/prices, never prose")
    void seatSetCardMirrorsCandidate() {
        UUID sessionId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        var candidate = new FindBestSeatsResult.SeatCandidate(List.of(seatId),
                List.of(new AvailableSeatItem(seatId, UUID.randomUUID(), "Stalls", "A", 7,
                        BigDecimal.ZERO, BigDecimal.ZERO, "STD", UUID.randomUUID(), 2200L, "EUR", "AVAILABLE")),
                2200L, "EUR", true, List.of("closest"), 1);

        AssistantCard card = assembler.seatSetCard(candidate, sessionId);

        assertThat(card.eventSessionId()).isEqualTo(sessionId);
        assertThat(card.seatIds()).containsExactly(seatId);
        assertThat(card.totalPriceMinor()).isEqualTo(2200L);
        assertThat(card.contiguous()).isTrue();
    }

    @Test
    @DisplayName("event/session cards preserve authoritative fields")
    void eventAndSessionCards() {
        UUID eventId = UUID.randomUUID();
        var event = new EventToolResult(eventId, "Hamlet", "Tragedy", "THEATRE",
                UUID.randomUUID(), "PUBLISHED");
        assertThat(assembler.eventCard(event).eventId()).isEqualTo(eventId);

        UUID sessionId = UUID.randomUUID();
        var sessions = new EventSessionsToolResult(eventId, List.of(
                new SessionToolItem(sessionId, Instant.now(), null, "SCHEDULED", null, null, "BOOKABLE")));
        assertThat(assembler.sessionCards(sessions)).hasSize(1);
        assertThat(assembler.sessionCards(sessions).getFirst().eventSessionId()).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("15: chat response never exposes chain-of-thought/reasoning")
    void responseNeverExposesReasoning() throws Exception {
        var response = new AssistantChatResponse(UUID.randomUUID(), "Hello", AssistantState.IDLE,
                List.of(), List.of(), null);
        String json = new ObjectMapper().writeValueAsString(response);
        assertThat(json).doesNotContain("chainOfThought");
        assertThat(json).doesNotContain("chain_of_thought");
        assertThat(json).doesNotContain("reasoning");
        assertThat(json).doesNotContain("GROQ_API_KEY");
        assertThat(json).doesNotContain("Bearer");
    }

    @Test
    @DisplayName("cards ignore unused search result")
    void searchCardsBounded() {
        var empty = new SearchEventsResult(List.of());
        assertThat(assembler.eventCards(empty)).isEmpty();
        assertThat(assembler.eventCards(null)).isEmpty();
    }
}
