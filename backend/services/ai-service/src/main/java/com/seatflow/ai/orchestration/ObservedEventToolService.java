package com.seatflow.ai.orchestration;

import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.service.AiMetrics;
import com.seatflow.ai.service.EventToolService;
import com.seatflow.ai.tool.dto.EventSessionsToolResult;
import com.seatflow.ai.tool.dto.EventToolResult;
import com.seatflow.ai.tool.dto.GetEventRequest;
import com.seatflow.ai.tool.dto.GetEventSessionsRequest;
import com.seatflow.ai.tool.dto.SearchEventsRequest;
import com.seatflow.ai.tool.dto.SearchEventsResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Recording decorator for event tools: delegates to the real service and observes the authoritative
 * DTO for the current model turn (when bound). Existing unit tests target the delegate impl
 * directly and are unaffected.
 *
 * <p>Also records bounded {@code seatflow_ai_tool_calls_total{tool,result}} metrics and the safe
 * {@code AI_TOOL_CALLED} lifecycle event (tool name + result category only, never arguments or
 * payloads).
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class ObservedEventToolService implements EventToolService {

    private final com.seatflow.ai.service.impl.EventToolServiceImpl delegate;
    private final AssistantToolObservation observation;
    private final AiMetrics metrics;

    @Override
    public SearchEventsResult searchEvents(SearchEventsRequest request, AiRequestContext context) {
        try {
            SearchEventsResult result = delegate.searchEvents(request, context);
            observation.recordSearchEvents(result);
            toolCalled("searchEvents", true, context);
            return result;
        } catch (RuntimeException ex) {
            toolCalled("searchEvents", false, context);
            throw ex;
        }
    }

    @Override
    public EventToolResult getEvent(GetEventRequest request, AiRequestContext context) {
        try {
            EventToolResult result = delegate.getEvent(request, context);
            observation.recordEvent(result);
            toolCalled("getEvent", true, context);
            return result;
        } catch (RuntimeException ex) {
            toolCalled("getEvent", false, context);
            throw ex;
        }
    }

    @Override
    public EventSessionsToolResult getEventSessions(GetEventSessionsRequest request,
                                                     AiRequestContext context) {
        try {
            EventSessionsToolResult result = delegate.getEventSessions(request, context);
            observation.recordSessions(result);
            toolCalled("getEventSessions", true, context);
            return result;
        } catch (RuntimeException ex) {
            toolCalled("getEventSessions", false, context);
            throw ex;
        }
    }

    private void toolCalled(String tool, boolean success, AiRequestContext context) {
        metrics.recordToolCall(tool, success);
        log.info("AI_TOOL_CALLED tool={} result={} correlationId={}", tool,
                success ? "success" : "error", context == null ? "N/A" : context.correlationId());
    }
}
