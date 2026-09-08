package com.seatflow.ai.client;

import com.seatflow.ai.client.dto.EventDetailClientDto;
import com.seatflow.ai.client.dto.EventSessionClientDto;
import com.seatflow.ai.client.dto.EventSummaryClientDto;
import com.seatflow.ai.client.dto.SeatMapClientDto;
import com.seatflow.ai.client.dto.SessionBookingContextClientDto;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.common.domain.dto.PagedResult;

import java.util.List;
import java.util.UUID;

/**
 * Read-only Event Service boundary for AI tools (TASK-P15-002 section 6).
 *
 * <p>All calls go through Eureka/LoadBalancer to the logical {@code event-service} name with the
 * caller's Bearer JWT and the ambient correlation ID. Implementations must throw typed tool
 * errors on failure; returning invented or empty substitute data is forbidden.
 */
public interface EventServiceClient {

    PagedResult<EventSummaryClientDto> searchEvents(
            String query, String category, int page, int size, AiRequestContext context);

    EventDetailClientDto getEvent(UUID eventId, AiRequestContext context);

    List<EventSessionClientDto> listSessions(UUID eventId, AiRequestContext context);

    /**
     * Resolve the server-derived booking context for one session (parent event, venue, statuses).
     * A 404 means the session does not exist; callers map it to an invalid-argument tool error.
     */
    SessionBookingContextClientDto getSessionBookingContext(UUID eventSessionId, AiRequestContext context);

    /**
     * Read the priced seat map for one event (venue layout + section pricing tiers + stage
     * elements). Used only together with a session booking context that proves the event owns the
     * requested inventory.
     */
    SeatMapClientDto getSeatMap(UUID eventId, AiRequestContext context);
}
