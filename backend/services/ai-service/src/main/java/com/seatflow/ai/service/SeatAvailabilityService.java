package com.seatflow.ai.service;

import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.tool.dto.AvailableSeatsResult;
import com.seatflow.ai.tool.dto.FindBestSeatsRequest;
import com.seatflow.ai.tool.dto.FindBestSeatsResult;
import com.seatflow.ai.tool.dto.GetAvailableSeatsRequest;

/**
 * Read-only domain logic behind the seat availability and best-seat AI tools.
 *
 * <p>Validates untrusted model input at this boundary, composes live downstream snapshots through
 * {@code SeatCandidateAssembler}, and ranks deterministically. The context must carry an
 * authenticated identity; anonymous calls fail instead of escalating privileges.
 */
public interface SeatAvailabilityService {

    AvailableSeatsResult getAvailableSeats(GetAvailableSeatsRequest request, AiRequestContext context);

    FindBestSeatsResult findBestSeats(FindBestSeatsRequest request, AiRequestContext context);
}
