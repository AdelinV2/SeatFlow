package com.seatflow.ai.orchestration;

import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.service.EventToolService;
import com.seatflow.ai.tool.dto.EventSessionsToolResult;
import com.seatflow.ai.tool.dto.EventToolResult;
import com.seatflow.ai.tool.dto.GetEventRequest;
import com.seatflow.ai.tool.dto.GetEventSessionsRequest;
import com.seatflow.ai.tool.dto.SearchEventsRequest;
import com.seatflow.ai.tool.dto.SearchEventsResult;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Recording decorator for event tools: delegates to the real service and observes the authoritative
 * DTO for the current model turn (when bound). Existing unit tests target the delegate impl
 * directly and are unaffected.
 */
@Service
@Primary
@RequiredArgsConstructor
public class ObservedEventToolService implements EventToolService {

    private final com.seatflow.ai.service.impl.EventToolServiceImpl delegate;
    private final AssistantToolObservation observation;

    @Override
    public SearchEventsResult searchEvents(SearchEventsRequest request, AiRequestContext context) {
        SearchEventsResult result = delegate.searchEvents(request, context);
        observation.recordSearchEvents(result);
        return result;
    }

    @Override
    public EventToolResult getEvent(GetEventRequest request, AiRequestContext context) {
        EventToolResult result = delegate.getEvent(request, context);
        observation.recordEvent(result);
        return result;
    }

    @Override
    public EventSessionsToolResult getEventSessions(GetEventSessionsRequest request,
                                                    AiRequestContext context) {
        EventSessionsToolResult result = delegate.getEventSessions(request, context);
        observation.recordSessions(result);
        return result;
    }
}
