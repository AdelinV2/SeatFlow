package com.seatflow.ai.service.impl;

import com.seatflow.ai.client.EventServiceClient;
import com.seatflow.ai.client.dto.EventDetailClientDto;
import com.seatflow.ai.client.dto.EventSessionClientDto;
import com.seatflow.ai.client.dto.EventSummaryClientDto;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;
import com.seatflow.ai.service.EventToolService;
import com.seatflow.ai.tool.dto.EventSearchItem;
import com.seatflow.ai.tool.dto.EventSessionsToolResult;
import com.seatflow.ai.tool.dto.EventToolResult;
import com.seatflow.ai.tool.dto.GetEventRequest;
import com.seatflow.ai.tool.dto.GetEventSessionsRequest;
import com.seatflow.ai.tool.dto.SearchEventsRequest;
import com.seatflow.ai.tool.dto.SearchEventsResult;
import com.seatflow.ai.tool.dto.SessionToolItem;
import com.seatflow.common.domain.dto.PagedResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Validates model input and normalizes Event Service snapshots for model consumption.
 *
 * <p>Bounds applied here: free-text query at most 100 characters (the stricter existing public
 * API limit), no control characters, search limit clamped to {@code 1..20} (default 5), session
 * limit clamped to {@code 1..50} (default 20), descriptions truncated to 500 characters.
 * Validation failures throw {@code INVALID_TOOL_ARGUMENT} before any downstream call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventToolServiceImpl implements EventToolService {

    static final int SEARCH_QUERY_MAX_LENGTH = 100;
    static final int SEARCH_DEFAULT_LIMIT = 5;
    static final int SEARCH_MAX_LIMIT = 20;
    static final int SESSIONS_DEFAULT_LIMIT = 20;
    static final int SESSIONS_MAX_LIMIT = 50;
    static final int DESCRIPTION_MAX_LENGTH = 500;

    private static final Set<String> KNOWN_CATEGORIES =
            Set.of("CONCERT", "THEATRE", "SPORTS", "CONFERENCE", "OTHER");

    private final EventServiceClient eventServiceClient;
    private final Clock clock;

    @Override
    public SearchEventsResult searchEvents(SearchEventsRequest request, AiRequestContext context) {
        requireAuthenticated(context);
        if (request == null) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "Search criteria are required.");
        }
        String query = normalizeQuery(request.query());
        String category = normalizeCategory(request.category());
        validateDateRange(request.startDate(), request.endDate());
        int limit = clampLimit(request.limit(), SEARCH_DEFAULT_LIMIT, SEARCH_MAX_LIMIT);

        PagedResult<EventSummaryClientDto> page =
                eventServiceClient.searchEvents(query, category, 0, limit, context);

        List<EventSearchItem> events = page.content().stream()
                .filter(entry -> withinDateBounds(entry.nextSessionStartsAt(),
                        request.startDate(), request.endDate()))
                .limit(limit)
                .map(entry -> new EventSearchItem(
                        entry.id(),
                        entry.title(),
                        entry.category(),
                        "PUBLISHED",
                        entry.nextSessionStartsAt(),
                        entry.minPrice(),
                        entry.maxPrice(),
                        entry.currency()))
                .toList();
        log.info("AI event search completed: matched={}, returned={}, queryPresent={}, category={}",
                page.totalElements(), events.size(), query != null, category);
        return new SearchEventsResult(events);
    }

    @Override
    public EventToolResult getEvent(GetEventRequest request, AiRequestContext context) {
        requireAuthenticated(context);
        if (request == null || request.eventId() == null || request.eventId().isBlank()) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "An eventId is required to read an event.");
        }
        UUID eventId = parseUuid(request.eventId().trim(), "eventId");
        EventDetailClientDto detail = eventServiceClient.getEvent(eventId, context);
        log.info("AI event lookup completed: eventId={}, titlePresent={}",
                eventId, detail.title() != null);
        return new EventToolResult(
                detail.id(),
                detail.title(),
                boundDescription(detail.description()),
                detail.category(),
                detail.venueId(),
                detail.status());
    }

    @Override
    public EventSessionsToolResult getEventSessions(
            GetEventSessionsRequest request, AiRequestContext context) {
        requireAuthenticated(context);
        if (request == null || request.eventId() == null || request.eventId().isBlank()) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "An eventId is required to list event sessions.");
        }
        UUID eventId = parseUuid(request.eventId().trim(), "eventId");
        if (request.from() != null && request.to() != null && request.to().isBefore(request.from())) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "Session range end must be on or after its start.");
        }
        int limit = clampLimit(request.limit(), SESSIONS_DEFAULT_LIMIT, SESSIONS_MAX_LIMIT);

        List<EventSessionClientDto> sessions = eventServiceClient.listSessions(eventId, context);
        List<SessionToolItem> items = sessions.stream()
                .filter(session -> withinInstantBounds(session.startsAt(), request.from(), request.to()))
                .limit(limit)
                .map(session -> new SessionToolItem(
                        session.id(),
                        session.startsAt(),
                        session.endsAt(),
                        session.status(),
                        session.saleStartsAt(),
                        session.saleEndsAt(),
                        bookableHint(session)))
                .toList();
        log.info("AI session lookup completed: eventId={}, returned={}", eventId, items.size());
        return new EventSessionsToolResult(eventId, items);
    }

    private void requireAuthenticated(AiRequestContext context) {
        if (context == null || !context.isAuthenticated()) {
            throw new AiToolException(AiToolError.UNAUTHENTICATED,
                    "Authentication is required to use the AI assistant tools.");
        }
    }

    private String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String trimmed = query.trim();
        if (trimmed.length() > SEARCH_QUERY_MAX_LENGTH) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "Search text must be at most " + SEARCH_QUERY_MAX_LENGTH + " characters.");
        }
        if (containsControlCharacters(trimmed)) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "Search text must not contain control characters.");
        }
        return trimmed;
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String canonical = category.trim().toUpperCase();
        if (!KNOWN_CATEGORIES.contains(canonical)) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "Unknown event category. Use one of " + String.join(", ", KNOWN_CATEGORIES) + ".");
        }
        return canonical;
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "Search end date must be on or after the start date.");
        }
    }

    private int clampLimit(Integer limit, int defaultLimit, int maxLimit) {
        if (limit == null) {
            return defaultLimit;
        }
        if (limit < 1) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "Result limit must be at least 1.");
        }
        return Math.min(limit, maxLimit);
    }

    private UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "The " + field + " must be a valid UUID.", ex);
        }
    }

    private boolean withinDateBounds(Instant instant, LocalDate startDate, LocalDate endDate) {
        if (startDate == null && endDate == null) {
            return true;
        }
        if (instant == null) {
            return false;
        }
        LocalDate date = instant.atZone(ZoneOffset.UTC).toLocalDate();
        if (startDate != null && date.isBefore(startDate)) {
            return false;
        }
        return endDate == null || !date.isAfter(endDate);
    }

    private boolean withinInstantBounds(Instant instant, Instant from, Instant to) {
        if (from == null && to == null) {
            return true;
        }
        if (instant == null) {
            return false;
        }
        if (from != null && instant.isBefore(from)) {
            return false;
        }
        return to == null || !instant.isAfter(to);
    }

    private String boundDescription(String description) {
        if (description == null) {
            return null;
        }
        if (description.length() <= DESCRIPTION_MAX_LENGTH) {
            return description;
        }
        return description.substring(0, DESCRIPTION_MAX_LENGTH) + "...";
    }

    private String bookableHint(EventSessionClientDto session) {
        if (!"SCHEDULED".equals(session.status())) {
            return "NOT_BOOKABLE";
        }
        Instant now = clock.instant();
        if (session.saleStartsAt() != null && now.isBefore(session.saleStartsAt())) {
            return "SALES_CLOSED";
        }
        if (session.saleEndsAt() != null && now.isAfter(session.saleEndsAt())) {
            return "SALES_CLOSED";
        }
        return "BOOKABLE";
    }

    private boolean containsControlCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
