package com.seatflow.ai.service.seat.impl;

import com.seatflow.ai.service.seat.PricedSeat;
import com.seatflow.ai.service.seat.SeatGeometry;
import com.seatflow.ai.service.seat.SeatRankingService;
import com.seatflow.ai.service.seat.SeatSnapshot;
import com.seatflow.ai.tool.dto.AvailableSeatItem;
import com.seatflow.ai.tool.dto.FindBestSeatsResult;
import com.seatflow.ai.tool.dto.SeatRankingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Deterministic best-seat ranking (TASK-P15-003 sections 5-8).
 *
 * <p>Pipeline:
 * <ol>
 *   <li>hard constraints first: quantity {@code 1..10} (checked by the caller), session-uniform
 *       snapshot (by construction), resolvable single-currency price (by construction), budget cap,
 *       currency filter, duplicate-seat exclusion;</li>
 *   <li>generate all valid contiguous windows per section+row (same {@code sectionId}, same
 *       normalized {@code rowLabel}, unique integer {@code seatNumber}s forming an exact
 *       consecutive sequence);</li>
 *   <li>when at least one contiguous set survives, rank contiguous sets only; otherwise generate a
 *       bounded number of non-contiguous alternatives marked {@code contiguous=false};</li>
 *   <li>score with lexicographic priority tuples per strategy — no opaque floating scores, no
 *       prompt-generated weights;</li>
 *   <li>return at most {@value #MAX_CANDIDATES} candidates, or a structured {@code NO_MATCH} with
 *       deterministic relaxation hints instead of a fabricated best match.</li>
 * </ol>
 *
 * <p>Strategy priority tuples (each key ascending unless noted):
 * <ul>
 *   <li>{@code CLOSEST_TO_STAGE}: contiguous first, preferred section/category match, smaller
 *       centroid distance to the stage center when derivable, lower total price, stable
 *       identifiers;</li>
 *   <li>{@code MOST_CENTRAL}: contiguous first, preferred match, smaller centroid distance to the
 *       venue bounding-box center, lower total price, stable identifiers;</li>
 *   <li>{@code BEST_VALUE}: contiguous first, preferred match, lower total price, better geometry
 *       quality (stage distance when available, else venue-center distance), stable
 *       identifiers.</li>
 * </ul>
 *
 * <p>Final tie breaker is always
 * {@code sectionId -> rowLabel -> minSeatNumber -> sorted seatIds}, so repeated calls over an
 * identical snapshot return identical ordering. When stage/venue geometry cannot be derived
 * reliably, the geometry key degrades to neutral (all-null, compared last-safe) rather than an
 * invented distance.
 */
@Slf4j
@Service
public class SeatRankingServiceImpl implements SeatRankingService {

    static final int MAX_CANDIDATES = 3;
    /** Deterministic bound on evaluated candidate windows; prevents combinatorial explosion. */
    static final int MAX_WINDOWS_EVALUATED = 2000;
    /** Deterministic bound on non-contiguous alternatives generated in the fallback path. */
    static final int MAX_NON_CONTIGUOUS_WINDOWS = 500;

    @Override
    public FindBestSeatsResult rank(SeatSnapshot snapshot, ValidatedBestSeatsQuery query) {
        Objects.requireNonNull(snapshot, "snapshot is required");
        Objects.requireNonNull(query, "query is required");
        if (query.quantity() < 1 || query.quantity() > 10) {
            throw new IllegalArgumentException("quantity must be between 1 and 10 inclusive");
        }

        List<PricedSeat> eligible = snapshot.seats().stream()
                .filter(seat -> snapshot.eventSessionId() != null)
                .filter(seat -> currencyEligible(seat, query.currency()))
                .toList();

        List<Candidate> contiguous = generateContiguous(eligible, query);
        List<Candidate> pool = !contiguous.isEmpty()
                ? contiguous
                : generateNonContiguous(eligible, query);

        List<Candidate> ranked = pool.stream()
                .sorted(comparatorFor(query.strategy(), snapshot, query))
                .limit(MAX_CANDIDATES)
                .toList();

        if (ranked.isEmpty()) {
            List<String> hints = relaxationHints(eligible, query);
            log.info("AI best-seat ranking found no match: eventSessionId={}, quantity={}, hints={}",
                    snapshot.eventSessionId(), query.quantity(), hints.size());
            return new FindBestSeatsResult(snapshot.eventSessionId(), snapshot.snapshotAt(),
                    "NO_MATCH", List.of(), hints, List.of());
        }

        List<FindBestSeatsResult.SeatCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            Candidate candidate = ranked.get(i);
            candidates.add(new FindBestSeatsResult.SeatCandidate(
                    candidate.seatIds(), candidate.displaySeats(), candidate.totalPriceMinor(),
                    candidate.currency(), candidate.contiguous(),
                    candidate.reasons(query.strategy(), snapshot), i + 1));
        }
        log.info("AI best-seat ranking completed: eventSessionId={}, quantity={}, strategy={}, "
                        + "contiguousPool={}, returned={}",
                snapshot.eventSessionId(), query.quantity(), query.strategy(),
                !contiguous.isEmpty(), candidates.size());
        return new FindBestSeatsResult(snapshot.eventSessionId(), snapshot.snapshotAt(),
                "OK", List.copyOf(candidates), List.of(), List.of());
    }

    private boolean currencyEligible(PricedSeat seat, String requestedCurrency) {
        return requestedCurrency == null
                || (seat.currency() != null && seat.currency().equalsIgnoreCase(requestedCurrency));
    }

    // ------------------------------------------------------------------
    // Candidate generation
    // ------------------------------------------------------------------

    private List<Candidate> generateContiguous(List<PricedSeat> eligible, ValidatedBestSeatsQuery query) {
        Map<RowKey, List<PricedSeat>> byRow = new LinkedHashMap<>();
        for (PricedSeat seat : eligible) {
            byRow.computeIfAbsent(new RowKey(seat.sectionId(), seat.rowLabel()), key -> new ArrayList<>())
                    .add(seat);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<RowKey, List<PricedSeat>> entry : byRow.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            List<PricedSeat> row = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(PricedSeat::seatNumber)
                            .thenComparing(PricedSeat::seatId))
                    .toList();
            for (int start = 0; start + query.quantity() <= row.size()
                    && candidates.size() < MAX_WINDOWS_EVALUATED; start++) {
                List<PricedSeat> window = row.subList(start, start + query.quantity());
                if (!isConsecutive(window)) {
                    continue;
                }
                toCandidate(window, true, query).ifPresent(candidates::add);
            }
        }
        return candidates;
    }

    private boolean isConsecutive(List<PricedSeat> window) {
        for (int i = 1; i < window.size(); i++) {
            if (window.get(i).seatNumber() != window.get(i - 1).seatNumber() + 1) {
                return false;
            }
        }
        return true;
    }

    private List<Candidate> generateNonContiguous(List<PricedSeat> eligible, ValidatedBestSeatsQuery query) {
        List<PricedSeat> ordered = eligible.stream()
                .sorted(Comparator.comparing(PricedSeat::sectionId)
                        .thenComparing(PricedSeat::rowLabel)
                        .thenComparingInt(PricedSeat::seatNumber)
                        .thenComparing(PricedSeat::seatId))
                .toList();
        List<Candidate> candidates = new ArrayList<>();
        for (int start = 0; start + query.quantity() <= ordered.size()
                && candidates.size() < MAX_NON_CONTIGUOUS_WINDOWS; start++) {
            List<PricedSeat> window = ordered.subList(start, start + query.quantity());
            if (isSameRowContiguous(window)) {
                continue;
            }
            toCandidate(window, false, query).ifPresent(candidates::add);
        }
        return candidates;
    }

    /** A fallback window that is actually contiguous was already considered; skip it here. */
    private boolean isSameRowContiguous(List<PricedSeat> window) {
        if (window.isEmpty()) {
            return false;
        }
        UUID sectionId = window.getFirst().sectionId();
        String rowLabel = window.getFirst().rowLabel();
        for (PricedSeat seat : window) {
            if (!Objects.equals(sectionId, seat.sectionId()) || !rowLabel.equals(seat.rowLabel())) {
                return false;
            }
        }
        return isConsecutive(window.stream()
                .sorted(Comparator.comparingInt(PricedSeat::seatNumber)).toList());
    }

    private Optional<Candidate> toCandidate(
            List<PricedSeat> seats, boolean contiguous, ValidatedBestSeatsQuery query) {
        if (seats.stream().map(PricedSeat::seatId).distinct().count() != seats.size()) {
            return Optional.empty();
        }
        List<String> currencies = seats.stream()
                .map(PricedSeat::currency)
                .filter(Objects::nonNull)
                .map(currency -> currency.trim().toUpperCase())
                .distinct()
                .toList();
        if (currencies.size() != 1) {
            return Optional.empty();
        }
        long total;
        try {
            total = seats.stream().mapToLong(PricedSeat::priceMinor).reduce(0L, Math::addExact);
        } catch (ArithmeticException ex) {
            return Optional.empty();
        }
        if (query.maxTotalPriceMinor() != null && total > query.maxTotalPriceMinor()) {
            return Optional.empty();
        }
        List<PricedSeat> ordered = seats.stream()
                .sorted(Comparator.comparing(PricedSeat::sectionId,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(PricedSeat::rowLabel)
                        .thenComparingInt(PricedSeat::seatNumber)
                        .thenComparing(PricedSeat::seatId))
                .toList();
        return Optional.of(new Candidate(ordered, contiguous, total, currencies.getFirst()));
    }

    // ------------------------------------------------------------------
    // Scoring
    // ------------------------------------------------------------------

    private Comparator<Candidate> comparatorFor(
            SeatRankingStrategy strategy, SeatSnapshot snapshot, ValidatedBestSeatsQuery query) {
        Comparator<Candidate> base = Comparator
                .comparing(Candidate::contiguous).reversed()
                .thenComparing(Comparator.comparingInt((Candidate candidate) ->
                        candidate.preferenceMatches(query)).reversed());
        Comparator<Candidate> geometry;
        Comparator<Candidate> price = Comparator.comparingLong(Candidate::totalPriceMinor);
        Comparator<Candidate> tieBreak = Comparator
                .comparing((Candidate candidate) -> candidate.firstSectionId(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Candidate::firstRowLabel)
                .thenComparingInt(Candidate::minSeatNumber)
                .thenComparing(Candidate::seatIds,
                        Comparator.comparing(List::toString));
        return switch (strategy) {
            case CLOSEST_TO_STAGE -> base
                    .thenComparing(geometryKey(snapshot.stageCenter()), nullsLastBigDecimal())
                    .thenComparing(price)
                    .thenComparing(tieBreak);
            case MOST_CENTRAL -> base
                    .thenComparing(geometryKey(snapshot.venueCenter()), nullsLastBigDecimal())
                    .thenComparing(price)
                    .thenComparing(tieBreak);
            case BEST_VALUE -> base
                    .thenComparing(price)
                    .thenComparing(geometryKey(preferredGeometry(snapshot)),
                            nullsLastBigDecimal())
                    .thenComparing(tieBreak);
        };
    }

    private Optional<SeatGeometry.Point> preferredGeometry(SeatSnapshot snapshot) {
        return snapshot.stageCenter().or(snapshot::venueCenter);
    }

    private java.util.function.Function<Candidate, BigDecimal> geometryKey(
            Optional<SeatGeometry.Point> reference) {
        return candidate -> candidate.centroidDistanceTo(reference).orElse(null);
    }

    private Comparator<BigDecimal> nullsLastBigDecimal() {
        return Comparator.nullsLast(Comparator.naturalOrder());
    }

    // ------------------------------------------------------------------
    // NO_MATCH hints
    // ------------------------------------------------------------------

    private List<String> relaxationHints(List<PricedSeat> eligible, ValidatedBestSeatsQuery query) {
        List<String> hints = new ArrayList<>();
        if (eligible.isEmpty()) {
            hints.add(query.currency() == null
                    ? "No priced available seats match the current filters."
                    : "No priced available seats match the current filters for currency "
                            + query.currency().trim().toUpperCase() + ".");
            return List.copyOf(hints);
        }
        if (eligible.size() < query.quantity()) {
            hints.add("Only " + eligible.size() + " priced available seat(s) match; "
                    + "reduce quantity to " + eligible.size() + " or widen the filters.");
        }
        if (query.maxTotalPriceMinor() != null) {
            cheapestTotal(eligible, query.quantity()).ifPresent(cheapest -> {
                if (cheapest > query.maxTotalPriceMinor()) {
                    hints.add("The cheapest " + query.quantity() + "-seat set costs " + cheapest
                            + " minor units; increase the budget or reduce quantity.");
                }
            });
        }
        if (hints.isEmpty()) {
            hints.add("No seat set satisfies all constraints together; "
                    + "try a smaller quantity, a larger budget, or different preferences.");
        }
        return List.copyOf(hints);
    }

    private Optional<Long> cheapestTotal(List<PricedSeat> eligible, int quantity) {
        Map<String, List<PricedSeat>> byCurrency = new LinkedHashMap<>();
        for (PricedSeat seat : eligible) {
            String currency = seat.currency() == null ? "" : seat.currency().trim().toUpperCase();
            byCurrency.computeIfAbsent(currency, key -> new ArrayList<>()).add(seat);
        }
        Long best = null;
        for (List<PricedSeat> group : byCurrency.values()) {
            if (group.size() < quantity) {
                continue;
            }
            long total;
            try {
                total = group.stream()
                        .mapToLong(PricedSeat::priceMinor)
                        .sorted()
                        .limit(quantity)
                        .reduce(0L, Math::addExact);
            } catch (ArithmeticException ex) {
                continue;
            }
            if (best == null || total < best) {
                best = total;
            }
        }
        return Optional.ofNullable(best);
    }

    // ------------------------------------------------------------------
    // Candidate value type
    // ------------------------------------------------------------------

    private record RowKey(UUID sectionId, String rowLabel) implements Comparable<RowKey> {
        @Override
        public int compareTo(RowKey other) {
            int sections = Comparator.<UUID>nullsFirst(Comparator.naturalOrder())
                    .compare(sectionId, other.sectionId);
            if (sections != 0) {
                return sections;
            }
            return rowLabel.compareTo(other.rowLabel);
        }
    }

    private static final class Candidate {
        private final List<PricedSeat> seats;
        private final boolean contiguous;
        private final long totalPriceMinor;
        private final String currency;

        private Candidate(List<PricedSeat> seats, boolean contiguous, long totalPriceMinor, String currency) {
            this.seats = List.copyOf(seats);
            this.contiguous = contiguous;
            this.totalPriceMinor = totalPriceMinor;
            this.currency = currency;
        }

        boolean contiguous() {
            return contiguous;
        }

        long totalPriceMinor() {
            return totalPriceMinor;
        }

        String currency() {
            return currency;
        }

        List<UUID> seatIds() {
            return seats.stream().map(PricedSeat::seatId).toList();
        }

        UUID firstSectionId() {
            return seats.stream().map(PricedSeat::sectionId).min(Comparator.naturalOrder()).orElse(null);
        }

        String firstRowLabel() {
            return seats.stream().map(PricedSeat::rowLabel).min(Comparator.naturalOrder()).orElse("");
        }

        int minSeatNumber() {
            return seats.stream().mapToInt(PricedSeat::seatNumber).min().orElse(Integer.MAX_VALUE);
        }

        List<AvailableSeatItem> displaySeats() {
            return seats.stream()
                    .map(seat -> new AvailableSeatItem(
                            seat.seatId(), seat.sectionId(), seat.sectionName(), seat.rowLabel(),
                            seat.seatNumber(),
                            seat.globalPoint().map(SeatGeometry.Point::x).orElse(null),
                            seat.globalPoint().map(SeatGeometry.Point::y).orElse(null),
                            seat.categoryName(), seat.pricingTierId(), seat.priceMinor(),
                            seat.currency(), "AVAILABLE"))
                    .toList();
        }

        int preferenceMatches(ValidatedBestSeatsQuery query) {
            int matches = 0;
            if (query.preferredSectionId() == null && query.preferredSectionName() == null
                    && query.preferredCategory() == null) {
                return 0;
            }
            if (matchesSection(query)) {
                matches++;
            }
            if (query.preferredCategory() != null && seats.stream()
                    .allMatch(seat -> seat.categoryName() != null
                            && seat.categoryName().trim().equalsIgnoreCase(query.preferredCategory()))) {
                matches++;
            }
            return matches;
        }

        private boolean matchesSection(ValidatedBestSeatsQuery query) {
            if (query.preferredSectionId() == null && query.preferredSectionName() == null) {
                return false;
            }
            boolean idOk = query.preferredSectionId() == null || seats.stream()
                    .allMatch(seat -> query.preferredSectionId().equals(seat.sectionId()));
            boolean nameOk = query.preferredSectionName() == null || seats.stream()
                    .allMatch(seat -> seat.sectionName() != null
                            && seat.sectionName().trim().equalsIgnoreCase(query.preferredSectionName()));
            return idOk && nameOk;
        }

        Optional<BigDecimal> centroidDistanceTo(Optional<SeatGeometry.Point> reference) {
            if (reference.isEmpty()) {
                return Optional.empty();
            }
            List<SeatGeometry.Point> points = seats.stream()
                    .map(PricedSeat::globalPoint)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();
            if (points.size() != seats.size()) {
                return Optional.empty();
            }
            BigDecimal sumX = points.stream().map(SeatGeometry.Point::x)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal sumY = points.stream().map(SeatGeometry.Point::y)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal count = BigDecimal.valueOf(points.size());
            SeatGeometry.Point centroid = new SeatGeometry.Point(
                    sumX.divide(count, 10, java.math.RoundingMode.HALF_UP),
                    sumY.divide(count, 10, java.math.RoundingMode.HALF_UP));
            return Optional.of(centroid.squaredDistanceTo(reference.get()));
        }

        List<String> reasons(SeatRankingStrategy strategy, SeatSnapshot snapshot) {
            List<String> reasons = new ArrayList<>();
            reasons.add(contiguous
                    ? "Contiguous seats in one section and row."
                    : "No contiguous set available; marked non-contiguous alternatives.");
            reasons.add(switch (strategy) {
                case CLOSEST_TO_STAGE -> snapshot.stageCenter().isPresent()
                        ? "Ranked closest to the stage."
                        : "Stage geometry unavailable; ranked by stable neutral order.";
                case MOST_CENTRAL -> snapshot.venueCenter().isPresent()
                        ? "Ranked closest to the venue center."
                        : "Venue geometry unavailable; ranked by stable neutral order.";
                case BEST_VALUE -> "Ranked by lowest total price, then location quality.";
            });
            reasons.add("Total " + totalPriceMinor + " " + currency + ".");
            return List.copyOf(reasons);
        }
    }
}
