package com.seatflow.ai.orchestration;

import com.seatflow.ai.api.dto.AssistantCard;
import com.seatflow.ai.tool.dto.AvailableSeatItem;
import com.seatflow.ai.tool.dto.EventSessionsToolResult;
import com.seatflow.ai.tool.dto.EventToolResult;
import com.seatflow.ai.tool.dto.FindBestSeatsResult;
import com.seatflow.ai.tool.dto.SearchEventsResult;
import com.seatflow.ai.tool.dto.SessionToolItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Assembles authoritative structured cards from validated tool results (TASK-P15-004 section 7).
 *
 * <p>Backend constructs cards from tool/application data wherever possible. Model prose may explain
 * cards but cannot mutate card fields.
 */
@Slf4j
@Component
public class AssistantCardAssembler {

    public List<AssistantCard> eventCards(SearchEventsResult result) {
        if (result == null || result.events() == null) {
            return List.of();
        }
        return result.events().stream()
                .limit(5)
                .map(item -> AssistantCard.event(
                        item.eventId(), item.title(), item.category(), publicSummary(item)))
                .toList();
    }

    public AssistantCard eventCard(EventToolResult result) {
        if (result == null) {
            return AssistantCard.info("Event unavailable",
                    "The requested event could not be loaded. Please try another search.");
        }
        String venueSummary = result.venueId() == null
                ? "Venue details available at checkout"
                : "Venue " + result.venueId();
        return AssistantCard.event(
                result.eventId(), result.title(), result.category(), venueSummary);
    }

    public List<AssistantCard> sessionCards(EventSessionsToolResult result) {
        if (result == null || result.sessions() == null) {
            return List.of();
        }
        return result.sessions().stream()
                .limit(5)
                .map(session -> AssistantCard.session(
                        result.eventId(), session.eventSessionId(), session.startsAt(),
                        session.endsAt(), session.status(), bookable(session)))
                .toList();
    }

    /**
     * Builds the SEAT_SET card directly from a validated candidate (never from model prose).
     */
    public AssistantCard seatSetCard(FindBestSeatsResult.SeatCandidate candidate, UUID eventSessionId) {
        List<UUID> seatIds = List.copyOf(candidate.seatIds());
        List<String> labels = candidate.seats() == null ? List.of() : candidate.seats().stream()
                .map(this::seatLabel)
                .toList();
        String sectionSummary = sectionSummary(candidate);
        return AssistantCard.seatSet(eventSessionId, seatIds, labels, sectionSummary,
                candidate.totalPriceMinor(), candidate.currency(),
                candidate.contiguous(), List.copyOf(candidate.reasons()));
    }

    /**
     * Builds the RESERVATION_PROPOSAL draft card only from the last authoritative
     * {@code findBestSeats} output (never by parsing model prose).
     */
    public AssistantCard proposalCard(ProposalDraft draft) {
        return AssistantCard.proposal(draft.eventId(), draft.eventSessionId(), draft.draftId(),
                draft.seatIds(), draft.seatLabels(), draft.sectionSummary(),
                draft.totalPriceMinor(), draft.currency(), draft.contiguous(), draft.reasons());
    }

    public AssistantCard infoCard(String title, String message) {
        return AssistantCard.info(title, message);
    }

    private String publicSummary(Object item) {
        if (item instanceof com.seatflow.ai.tool.dto.EventSearchItem searchItem) {
            StringBuilder summary = new StringBuilder();
            if (searchItem.category() != null) {
                summary.append(searchItem.category());
            }
            if (searchItem.nextSessionStartsAt() != null) {
                if (!summary.isEmpty()) {
                    summary.append(" · ");
                }
                summary.append("Next session ").append(searchItem.nextSessionStartsAt());
            }
            if (searchItem.currency() != null) {
                if (!summary.isEmpty()) {
                    summary.append(" · ");
                }
                summary.append(searchItem.currency());
            }
            return summary.isEmpty() ? "Published event" : summary.toString();
        }
        return "Published event";
    }

    private String bookable(SessionToolItem session) {
        if (session.bookableHint() != null && !session.bookableHint().isBlank()) {
            return session.bookableHint();
        }
        return "SCHEDULED".equals(session.status()) ? "BOOKABLE" : "NOT_BOOKABLE";
    }

    private String seatLabel(AvailableSeatItem seat) {
        String row = seat.rowLabel() == null ? "?" : seat.rowLabel();
        String section = seat.sectionName() == null ? "" : seat.sectionName() + " ";
        return (section + "Row " + row + " Seat " + seat.seatNumber()).trim();
    }

    private String sectionSummary(FindBestSeatsResult.SeatCandidate candidate) {
        if (candidate.seats() == null || candidate.seats().isEmpty()) {
            return "Selected seats";
        }
        String sections = candidate.seats().stream()
                .map(seat -> seat.sectionName() == null ? "?" : seat.sectionName())
                .distinct()
                .collect(Collectors.joining(", "));
        String rows = candidate.seats().stream()
                .map(seat -> seat.rowLabel() == null ? "?" : seat.rowLabel())
                .distinct()
                .collect(Collectors.joining(", "));
        return "Section(s) " + sections + " · Row(s) " + rows;
    }

    public List<AssistantCard> cardsForBestSeats(FindBestSeatsResult result) {
        if (result == null || !"OK".equals(result.status())
                || result.candidates() == null || result.candidates().isEmpty()) {
            return List.of();
        }
        List<AssistantCard> cards = new ArrayList<>();
        FindBestSeatsResult.SeatCandidate best = result.candidates().getFirst();
        cards.add(seatSetCard(best, result.eventSessionId()));
        return cards;
    }
}
