package com.seatflow.ai.orchestration;

import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.service.SeatAvailabilityService;
import com.seatflow.ai.tool.dto.AvailableSeatsResult;
import com.seatflow.ai.tool.dto.FindBestSeatsRequest;
import com.seatflow.ai.tool.dto.FindBestSeatsResult;
import com.seatflow.ai.tool.dto.GetAvailableSeatsRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Recording decorator for seat tools: delegates to the real service and observes the authoritative
 * DTOs (including the {@code findBestSeats} request fingerprint for supersede decisions) for the
 * current model turn when bound.
 */
@Service
@Primary
@RequiredArgsConstructor
public class ObservedSeatAvailabilityService implements SeatAvailabilityService {

    private final com.seatflow.ai.service.impl.SeatAvailabilityServiceImpl delegate;
    private final AssistantToolObservation observation;

    @Override
    public AvailableSeatsResult getAvailableSeats(GetAvailableSeatsRequest request,
                                                 AiRequestContext context) {
        AvailableSeatsResult result = delegate.getAvailableSeats(request, context);
        observation.recordAvailableSeats(result);
        return result;
    }

    @Override
    public FindBestSeatsResult findBestSeats(FindBestSeatsRequest request, AiRequestContext context) {
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
        return result;
    }
}
