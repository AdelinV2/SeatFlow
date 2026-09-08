package com.seatflow.ai.orchestration;

import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.service.AiMetrics;
import com.seatflow.ai.service.SeatAvailabilityService;
import com.seatflow.ai.tool.dto.AvailableSeatsResult;
import com.seatflow.ai.tool.dto.FindBestSeatsRequest;
import com.seatflow.ai.tool.dto.FindBestSeatsResult;
import com.seatflow.ai.tool.dto.GetAvailableSeatsRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Recording decorator for seat tools: delegates to the real service and observes the authoritative
 * DTOs (including the {@code findBestSeats} request fingerprint for supersede decisions) for the
 * current model turn when bound.
 *
 * <p>Also records bounded {@code seatflow_ai_tool_calls_total{tool,result}} metrics and the safe
 * {@code AI_TOOL_CALLED} lifecycle event (tool name + result category only, never arguments or
 * payloads).
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class ObservedSeatAvailabilityService implements SeatAvailabilityService {

    private final com.seatflow.ai.service.impl.SeatAvailabilityServiceImpl delegate;
    private final AssistantToolObservation observation;
    private final AiMetrics metrics;

    @Override
    public AvailableSeatsResult getAvailableSeats(GetAvailableSeatsRequest request,
                                                 AiRequestContext context) {
        try {
            AvailableSeatsResult result = delegate.getAvailableSeats(request, context);
            observation.recordAvailableSeats(result);
            toolCalled("getAvailableSeats", true, context);
            return result;
        } catch (RuntimeException ex) {
            toolCalled("getAvailableSeats", false, context);
            throw ex;
        }
    }

    @Override
    public FindBestSeatsResult findBestSeats(FindBestSeatsRequest request, AiRequestContext context) {
        try {
            FindBestSeatsResult result = delegate.findBestSeats(request, context);
            observation.recordBestSeats(new AssistantToolObservation.FindBestSeatsRequestSnapshot(
                    request.eventSessionId(),
                    request.quantity(),
                    request.maxTotalPriceMinor(),
                    request.currency(),
                    request.preferredSectionId(),
                    request.preferredSectionName(),
                    request.preferredCategory(),
                    request.strategy() == null ? null : request.strategy().name(),
                    null), result);
            toolCalled("findBestSeats", true, context);
            return result;
        } catch (RuntimeException ex) {
            toolCalled("findBestSeats", false, context);
            throw ex;
        }
    }

    private void toolCalled(String tool, boolean success, AiRequestContext context) {
        metrics.recordToolCall(tool, success);
        log.info("AI_TOOL_CALLED tool={} result={} correlationId={}", tool,
                success ? "success" : "error", context == null ? "N/A" : context.correlationId());
    }
}
