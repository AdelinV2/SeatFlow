package com.seatflow.ai.service.seat;

import com.seatflow.ai.client.EventServiceClient;
import com.seatflow.ai.client.ReservationAvailabilityClient;
import com.seatflow.ai.client.dto.SeatAvailabilityClientDto;
import com.seatflow.ai.client.dto.SeatMapClientDto;
import com.seatflow.ai.client.dto.SessionBookingContextClientDto;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.service.seat.SeatPriceResolver.PriceResolution;
import com.seatflow.ai.tool.dto.AvailableSeatItem;
import com.seatflow.ai.tool.dto.AvailableSeatsResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Composition layer for seat-focused AI tools (TASK-P15-003 section 11).
 *
 * <p>Joins three read-only responses by stable identifiers for one inventory snapshot:
 * Reservation Service availability (authoritative for status), the Event Service seat map (seat
 * identity, layout, pricing tiers, stage elements), and the session booking context (parent event
 * proof). No AI-owned inventory cache exists: every call re-reads all three sources, and any
 * downstream timeout surfaces a tool failure instead of stale data.
 *
 * <p>Seats missing from the seat map but present in availability (and vice versa for holds) are
 * excluded safely with a bounded warning metric; duplicates are collapsed deterministically.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatCandidateAssembler {

    private final EventServiceClient eventServiceClient;
    private final ReservationAvailabilityClient reservationAvailabilityClient;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /**
     * Assemble the priced available-seat snapshot for one session.
     *
     * @param eventSessionId validated session id
     * @param sectionId optional section filter, or {@code null}
     * @param preferredCategory optional exact category filter, or {@code null} for the canonical
     *     default tier
     * @param requestedCurrency optional ISO currency filter, or {@code null}
     * @param context authenticated caller context
     */
    public AssembledSnapshot assemble(
            UUID eventSessionId, UUID sectionId, String preferredCategory,
            String requestedCurrency, AiRequestContext context) {
        SessionBookingContextClientDto bookingContext =
                eventServiceClient.getSessionBookingContext(eventSessionId, context);
        SeatMapClientDto seatMap = eventServiceClient.getSeatMap(bookingContext.eventId(), context);
        SeatAvailabilityClientDto availability =
                reservationAvailabilityClient.getSeatAvailability(eventSessionId, context);

        Set<UUID> blockedSeats = availability.seatStatuses() == null ? Set.of()
                : availability.seatStatuses().stream()
                        .filter(entry -> entry != null && entry.seatId() != null
                                && isBlocking(entry.status()))
                        .map(SeatAvailabilityClientDto.SeatStatusEntry::seatId)
                        .collect(Collectors.toSet());

        Set<UUID> releasedEntries = availability.seatStatuses() == null ? Set.of()
                : availability.seatStatuses().stream()
                        .filter(entry -> entry != null && entry.seatId() != null)
                        .filter(entry -> !isBlocking(entry.status()))
                        .map(SeatAvailabilityClientDto.SeatStatusEntry::seatId)
                        .collect(Collectors.toSet());
        if (!releasedEntries.isEmpty()) {
            recordMismatch("released-entry", releasedEntries.size());
        }

        boolean geometryReliable = geometryReliable(seatMap);
        Optional<SeatGeometry.Point> stageCenter = geometryReliable
                ? resolveStageCenter(seatMap) : Optional.empty();

        List<PricedSeat> priced = new ArrayList<>();
        Map<UUID, AvailableSeatsResult.PricingSelectionHint> selectionHints = new LinkedHashMap<>();
        Set<UUID> seatMapSeatIds = new HashSet<>();
        int duplicateSeats = 0;
        Set<UUID> seen = new HashSet<>();

        for (SeatMapClientDto.SeatMapSection section :
                seatMap.sections() == null ? List.<SeatMapClientDto.SeatMapSection>of() : seatMap.sections()) {
            if (section == null || !Boolean.TRUE.equals(section.isActive())) {
                continue;
            }
            if (section.sectionId() == null) {
                recordMismatch("unidentified-section", 1);
                continue;
            }
            if (sectionId != null && !sectionId.equals(section.sectionId())) {
                continue;
            }
            for (SeatMapClientDto.SeatMapSeat seat :
                    section.seats() == null ? List.<SeatMapClientDto.SeatMapSeat>of() : section.seats()) {
                if (seat == null || seat.seatId() == null || !Boolean.TRUE.equals(seat.isActive())) {
                    continue;
                }
                if (seat.seatNumber() == null) {
                    continue;
                }
                if (!seen.add(seat.seatId())) {
                    duplicateSeats++;
                    continue;
                }
                seatMapSeatIds.add(seat.seatId());
                if (blockedSeats.contains(seat.seatId())) {
                    continue;
                }
                PriceResolution resolution = SeatPriceResolver.resolve(
                        section.pricingTiers(), preferredCategory, requestedCurrency);
                if (resolution instanceof PriceResolution.SelectionRequired selection) {
                    selectionHints.putIfAbsent(section.sectionId(),
                            new AvailableSeatsResult.PricingSelectionHint(
                                    section.sectionId(), section.name(),
                                    selection.availableCategories()));
                    continue;
                }
                if (!(resolution instanceof PriceResolution.Resolved resolved)) {
                    continue;
                }
                Optional<SeatGeometry.Point> global = geometryReliable
                        ? SeatGeometry.globalPoint(seat.positionX(), seat.positionY(),
                                section.positionX(), section.positionY())
                        : Optional.empty();
                priced.add(new PricedSeat(
                        seat.seatId(), section.sectionId(), section.name(),
                        SeatGeometry.normalizeRowLabel(seat.rowLabel()), seat.seatNumber(),
                        global, resolved.categoryName(), resolved.pricingTierId(),
                        resolved.priceMinor(), resolved.currency()));
            }
        }

        if (duplicateSeats > 0) {
            recordMismatch("duplicate-seat", duplicateSeats);
        }
        long unknownSeatRefs = blockedSeats.stream()
                .filter(seatId -> !seatMapSeatIds.contains(seatId))
                .count();
        if (unknownSeatRefs > 0) {
            recordMismatch("unknown-seat", (int) unknownSeatRefs);
        }

        priced.sort(Comparator.comparing(PricedSeat::sectionId, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(PricedSeat::rowLabel)
                .thenComparingInt(PricedSeat::seatNumber)
                .thenComparing(PricedSeat::seatId));

        List<SeatGeometry.Point> points = priced.stream()
                .map(PricedSeat::globalPoint)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
        Optional<SeatGeometry.Point> venueCenter =
                geometryReliable ? SeatGeometry.venueCenter(points) : Optional.empty();

        Instant snapshotAt = clock.instant();
        SeatSnapshot snapshot = new SeatSnapshot(
                bookingContext.eventSessionId(), snapshotAt, priced, stageCenter, venueCenter,
                geometryReliable);
        log.info("AI seat snapshot assembled: eventSessionId={}, eventId={}, pricedSeats={}, "
                        + "blockedSeats={}, selectionHints={}, geometryReliable={}",
                bookingContext.eventSessionId(), bookingContext.eventId(), priced.size(),
                blockedSeats.size(), selectionHints.size(), geometryReliable);
        return new AssembledSnapshot(snapshot, List.copyOf(selectionHints.values()));
    }

    public record AssembledSnapshot(
            SeatSnapshot snapshot, List<AvailableSeatsResult.PricingSelectionHint> selectionHints) {}

    /**
     * Any non-released status blocks the seat. {@code RELEASED} entries must not appear in the
     * active-hold query, but if one ever does it must not hide an available seat.
     */
    static boolean isBlocking(String status) {
        return status == null || !"RELEASED".equalsIgnoreCase(status.trim());
    }

    private boolean geometryReliable(SeatMapClientDto seatMap) {
        if (seatMap.sections() == null) {
            return true;
        }
        return seatMap.sections().stream()
                .filter(Objects::nonNull)
                .noneMatch(section -> SeatGeometry.hasRotation(section.rotationDeg()));
    }

    /**
     * Deterministic primary-stage choice: lowest {@code zIndex}, then stable {@code elementId}.
     * Only {@code STAGE} elements with valid rectangular geometry qualify.
     */
    static Optional<SeatGeometry.Point> resolveStageCenter(SeatMapClientDto seatMap) {
        if (seatMap.layoutElements() == null) {
            return Optional.empty();
        }
        return seatMap.layoutElements().stream()
                .filter(Objects::nonNull)
                .filter(element -> element.type() != null
                        && "STAGE".equalsIgnoreCase(element.type().trim()))
                .filter(element -> element.geometry() != null)
                .sorted(Comparator
                        .comparing((SeatMapClientDto.SeatMapLayoutElement element) -> element.zIndex(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(element -> element.elementId(),
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .map(element -> SeatGeometry.stageCenter(
                        element.geometry().x(), element.geometry().y(),
                        element.geometry().width(), element.geometry().height()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    public AvailableSeatItem toDisplayItem(PricedSeat seat) {
        return new AvailableSeatItem(
                seat.seatId(), seat.sectionId(), seat.sectionName(), seat.rowLabel(),
                seat.seatNumber(),
                seat.globalPoint().map(SeatGeometry.Point::x).orElse(null),
                seat.globalPoint().map(SeatGeometry.Point::y).orElse(null),
                seat.categoryName(), seat.pricingTierId(), seat.priceMinor(),
                seat.currency(), "AVAILABLE");
    }

    private void recordMismatch(String reason, int count) {
        try {
            Counter.builder("seatflow.ai.seat.mismatch.total")
                    .description("Seat-map/availability mismatches excluded safely from AI snapshots")
                    .tag("reason", reason)
                    .register(meterRegistry)
                    .increment(count);
        } catch (RuntimeException ex) {
            log.warn("Failed to record AI seat mismatch metric: reason={}", reason, ex);
        }
        log.warn("AI seat snapshot excluded {} entr(ies): reason={}", count, reason);
    }
}
