package com.seatflow.ai.orchestration;

import com.seatflow.ai.tool.dto.AvailableSeatsResult;
import com.seatflow.ai.tool.dto.EventSessionsToolResult;
import com.seatflow.ai.tool.dto.EventToolResult;
import com.seatflow.ai.tool.dto.FindBestSeatsResult;
import com.seatflow.ai.tool.dto.SearchEventsResult;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Observes authoritative tool DTOs executed during a model turn without parsing model prose.
 *
 * <p>During {@code ChatClient} tool execution (same thread, guarded by the per-conversation
 * single-flight lock), the observed service decorators record the exact DTOs returned by
 * {@code EventToolService} and {@code SeatAvailabilityService}. The orchestrator binds the current
 * conversation ID in a {@code ThreadLocal} before the model call and reads the recorded DTOs
 * afterwards to build cards and the proposal draft directly from tool data.
 *
 * <p>No JWT, API key, lock key, or owner metadata is stored here; only compact customer-safe DTOs.
 */
@Component
public class AssistantToolObservation {

    private final ThreadLocal<UUID> currentConversation = new ThreadLocal<>();
    private final ConcurrentHashMap<UUID, ObservedTurn> observed = new ConcurrentHashMap<>();

    public void bind(UUID conversationId) {
        currentConversation.set(conversationId);
        observed.put(conversationId, new ObservedTurn());
    }

    public void unbind() {
        UUID id = currentConversation.get();
        currentConversation.remove();
        if (id != null) {
            observed.remove(id);
        }
    }

    /** Returns the observed turn for the calling thread's bound conversation, if any. */
    public ObservedTurn current() {
        UUID id = currentConversation.get();
        return id == null ? null : observed.get(id);
    }

    public void recordSearchEvents(SearchEventsResult result) {
        ObservedTurn turn = current();
        if (turn != null) {
            turn.searchEvents = result;
        }
    }

    public void recordEvent(EventToolResult result) {
        ObservedTurn turn = current();
        if (turn != null) {
            turn.event = result;
        }
    }

    public void recordSessions(EventSessionsToolResult result) {
        ObservedTurn turn = current();
        if (turn != null) {
            turn.sessions = result;
        }
    }

    public void recordAvailableSeats(AvailableSeatsResult result) {
        ObservedTurn turn = current();
        if (turn != null) {
            turn.availableSeats = result;
        }
    }

    public void recordBestSeats(FindBestSeatsRequestSnapshot request, FindBestSeatsResult result) {
        ObservedTurn turn = current();
        if (turn != null) {
            turn.bestSeatsRequest = request;
            turn.bestSeats = result;
        }
    }

    public static class ObservedTurn {
        public volatile SearchEventsResult searchEvents;
        public volatile EventToolResult event;
        public volatile EventSessionsToolResult sessions;
        public volatile AvailableSeatsResult availableSeats;
        public volatile FindBestSeatsRequestSnapshot bestSeatsRequest;
        public volatile FindBestSeatsResult bestSeats;
    }

    /**
     * Minimal fingerprint inputs for supersede decisions (event/session/quantity/budget/currency/
     * section/category/strategy). Never contains secrets.
     */
    public record FindBestSeatsRequestSnapshot(
            String eventSessionId,
            Integer quantity,
            Long maxTotalPriceMinor,
            String currency,
            String preferredSectionId,
            String preferredSectionName,
            String preferredCategory,
            String strategy,
            String eventId) {
    }
}
