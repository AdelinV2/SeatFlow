package com.seatflow.ai.tool;

import com.seatflow.ai.context.AiRequestContextFactory;
import com.seatflow.ai.service.SeatAvailabilityService;
import com.seatflow.ai.tool.dto.AvailableSeatsResult;
import com.seatflow.ai.tool.dto.FindBestSeatsRequest;
import com.seatflow.ai.tool.dto.FindBestSeatsResult;
import com.seatflow.ai.tool.dto.GetAvailableSeatsRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Controlled read-only Spring AI tools for seat availability and deterministic best-seat ranking
 * (TASK-P15-003).
 *
 * <p>Methods are thin delegates: authentication comes from the server-side request context (never
 * from model input), validation and normalization live in {@link SeatAvailabilityService}, and
 * HTTP calls live in the dedicated client layer. Ranking is computed by deterministic Java code;
 * the model chooses constraints and explains returned results but never calculates availability,
 * price, adjacency, or ranking itself.
 */
@Component
@RequiredArgsConstructor
public class SeatAvailabilityTools {

    private final SeatAvailabilityService seatAvailabilityService;
    private final AiRequestContextFactory requestContexts;

    @Tool(name = "getAvailableSeats",
            description = "Read the authoritative available-seat snapshot for one eventSessionId UUID "
                    + "string with optional section, category, budget, and currency filters. Returns "
                    + "only AVAILABLE active seats with exact minor-unit prices. Snapshots are not "
                    + "holds; never invent seats and report failures instead of guessing.")
    public AvailableSeatsResult getAvailableSeats(
            @ToolParam(description = "Typed seat-availability criteria for one event session",
                    required = false)
            GetAvailableSeatsRequest request) {
        return seatAvailabilityService.getAvailableSeats(request, requestContexts.requireAuthenticated());
    }

    @Tool(name = "findBestSeats",
            description = "Rank up to 3 deterministic candidate seat sets for one eventSessionId UUID "
                    + "string, a quantity of 1..10, an optional total budget in minor units, and a "
                    + "strategy of CLOSEST_TO_STAGE, MOST_CENTRAL, or BEST_VALUE. Contiguous "
                    + "same-row sets are preferred and explicitly flagged; non-matches return "
                    + "NO_MATCH with hints instead of a fabricated best match.")
    public FindBestSeatsResult findBestSeats(
            @ToolParam(description = "Typed best-seat ranking criteria for one event session")
            FindBestSeatsRequest request) {
        return seatAvailabilityService.findBestSeats(request, requestContexts.requireAuthenticated());
    }
}
