package com.seatflow.ai.service.impl;

import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;
import com.seatflow.ai.service.SeatAvailabilityService;
import com.seatflow.ai.service.seat.SeatCandidateAssembler;
import com.seatflow.ai.service.seat.SeatRankingService;
import com.seatflow.ai.service.seat.SeatSnapshot;
import com.seatflow.ai.tool.dto.AvailableSeatItem;
import com.seatflow.ai.tool.dto.AvailableSeatsResult;
import com.seatflow.ai.tool.dto.FindBestSeatsRequest;
import com.seatflow.ai.tool.dto.FindBestSeatsResult;
import com.seatflow.ai.tool.dto.GetAvailableSeatsRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Validates model input and normalizes seat snapshots for model consumption (TASK-P15-003).
 *
 * <p>Bounds applied here: quantity must be {@code 1..10} (never silently reduced),
 * {@code getAvailableSeats} limit clamped to {@code 1..50} (default 20), currency normalized to
 * uppercase ISO codes, UUIDs parsed before any downstream call. Validation failures throw
 * {@code INVALID_TOOL_ARGUMENT} before any downstream call.
 *
 * <p>Budget semantics: {@code getAvailableSeats.maxTotalPriceMinor} is a per-seat cap (a seat the
 * caller could not afford alone is not a candidate); {@code findBestSeats.maxTotalPriceMinor} is
 * a whole-set cap enforced as a hard constraint during ranking.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatAvailabilityServiceImpl implements SeatAvailabilityService {

    static final int AVAILABLE_DEFAULT_LIMIT = 20;
    static final int AVAILABLE_MAX_LIMIT = 50;
    static final int MIN_QUANTITY = 1;
    static final int MAX_QUANTITY = 10;

    private final SeatCandidateAssembler assembler;
    private final SeatRankingService rankingService;

    @Override
    public AvailableSeatsResult getAvailableSeats(GetAvailableSeatsRequest request, AiRequestContext context) {
        requireAuthenticated(context);
        if (request == null || request.eventSessionId() == null || request.eventSessionId().isBlank()) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "An eventSessionId is required to read seat availability.");
        }
        UUID eventSessionId = parseUuid(request.eventSessionId().trim(), "eventSessionId");
        UUID sectionId = parseOptionalUuid(request.sectionId(), "sectionId");
        String category = normalizeOptionalName(request.category(), "category");
        String currency = normalizeOptionalCurrency(request.currency());
        Long budget = validateNonNegative(request.maxTotalPriceMinor(), "maxTotalPriceMinor");
        int limit = clampLimit(request.limit(), AVAILABLE_DEFAULT_LIMIT, AVAILABLE_MAX_LIMIT);

        SeatCandidateAssembler.AssembledSnapshot assembled =
                assembler.assemble(eventSessionId, sectionId, category, currency, context);

        List<AvailableSeatItem> seats = assembled.snapshot().seats().stream()
                .filter(seat -> budget == null || seat.priceMinor() <= budget)
                .limit(limit)
                .map(assembler::toDisplayItem)
                .toList();
        String resultCurrency = singleCurrency(seats);
        log.info("AI available-seats lookup completed: eventSessionId={}, returned={}, hints={}",
                eventSessionId, seats.size(), assembled.selectionHints().size());
        return new AvailableSeatsResult(eventSessionId, assembled.snapshot().snapshotAt(),
                resultCurrency, seats, assembled.selectionHints());
    }

    @Override
    public FindBestSeatsResult findBestSeats(FindBestSeatsRequest request, AiRequestContext context) {
        requireAuthenticated(context);
        if (request == null || request.eventSessionId() == null || request.eventSessionId().isBlank()) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "An eventSessionId is required to rank seats.");
        }
        UUID eventSessionId = parseUuid(request.eventSessionId().trim(), "eventSessionId");
        int quantity = validateQuantity(request.quantity());
        if (request.strategy() == null) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "A ranking strategy is required: CLOSEST_TO_STAGE, MOST_CENTRAL, or BEST_VALUE.");
        }
        UUID preferredSectionId = parseOptionalUuid(request.preferredSectionId(), "preferredSectionId");
        String preferredSectionName =
                normalizeOptionalName(request.preferredSectionName(), "preferredSectionName");
        String preferredCategory =
                normalizeOptionalName(request.preferredCategory(), "preferredCategory");
        String currency = normalizeOptionalCurrency(request.currency());
        Long budget = validateNonNegative(request.maxTotalPriceMinor(), "maxTotalPriceMinor");

        SeatCandidateAssembler.AssembledSnapshot assembled =
                assembler.assemble(eventSessionId, null, preferredCategory, currency, context);

        if (!assembled.selectionHints().isEmpty() && assembled.snapshot().seats().isEmpty()) {
            log.info("AI best-seat ranking requires pricing selection: eventSessionId={}, hints={}",
                    eventSessionId, assembled.selectionHints().size());
            return new FindBestSeatsResult(eventSessionId, assembled.snapshot().snapshotAt(),
                    "PRICING_SELECTION_REQUIRED", List.of(), List.of(), assembled.selectionHints());
        }

        SeatSnapshot snapshot = assembled.snapshot();
        SeatRankingService.ValidatedBestSeatsQuery query =
                new SeatRankingService.ValidatedBestSeatsQuery(quantity, budget, currency,
                        preferredSectionId, preferredSectionName, preferredCategory, request.strategy());
        FindBestSeatsResult ranked = rankingService.rank(snapshot, query);
        if (assembled.selectionHints().isEmpty()) {
            return ranked;
        }
        return new FindBestSeatsResult(ranked.eventSessionId(), ranked.snapshotAt(),
                ranked.status(), ranked.candidates(), ranked.relaxationHints(),
                assembled.selectionHints());
    }

    private String singleCurrency(List<AvailableSeatItem> seats) {
        List<String> currencies = seats.stream()
                .map(AvailableSeatItem::currency)
                .filter(currency -> currency != null && !currency.isBlank())
                .map(currency -> currency.trim().toUpperCase())
                .distinct()
                .toList();
        return currencies.size() == 1 ? currencies.getFirst() : null;
    }

    private int validateQuantity(Integer quantity) {
        if (quantity == null) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "A seat quantity between 1 and 10 is required.");
        }
        if (quantity < MIN_QUANTITY || quantity > MAX_QUANTITY) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "Seat quantity must be between 1 and 10 inclusive.");
        }
        return quantity;
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

    private Long validateNonNegative(Long value, String field) {
        if (value == null) {
            return null;
        }
        if (value < 0) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "The " + field + " must not be negative.");
        }
        return value;
    }

    private UUID parseOptionalUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseUuid(value.trim(), field);
    }

    private UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "The " + field + " must be a valid UUID.", ex);
        }
    }

    private String normalizeOptionalName(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > 200) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "The " + field + " must be at most 200 characters.");
        }
        return trimmed;
    }

    private String normalizeOptionalCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return null;
        }
        String normalized = currency.trim().toUpperCase();
        if (!normalized.matches("[A-Z]{3}")) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "Currency must be a 3-letter ISO-4217 code.");
        }
        return normalized;
    }

    private void requireAuthenticated(AiRequestContext context) {
        if (context == null || !context.isAuthenticated()) {
            throw new AiToolException(AiToolError.UNAUTHENTICATED,
                    "Authentication is required to use the AI assistant tools.");
        }
    }
}
