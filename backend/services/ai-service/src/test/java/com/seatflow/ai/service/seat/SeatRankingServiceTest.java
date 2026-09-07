package com.seatflow.ai.service.seat;

import com.seatflow.ai.service.seat.SeatRankingService.ValidatedBestSeatsQuery;
import com.seatflow.ai.tool.dto.FindBestSeatsResult;
import com.seatflow.ai.tool.dto.SeatRankingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatRankingServiceTest {

    private static final UUID SESSION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SECTION_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SECTION_B = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");

    private SeatRankingService ranking;

    @BeforeEach
    void setUp() {
        ranking = new com.seatflow.ai.service.seat.impl.SeatRankingServiceImpl();
    }

    private static PricedSeat seat(UUID sectionId, String sectionName, String row, int number,
                                   long priceMinor, String currency, int x, int y) {
        return new PricedSeat(UUID.randomUUID(), sectionId, sectionName, row, number,
                Optional.of(new SeatGeometry.Point(BigDecimal.valueOf(x), BigDecimal.valueOf(y))),
                "Standard", UUID.randomUUID(), priceMinor, currency);
    }

    private static PricedSeat plainSeat(UUID sectionId, String row, int number, long priceMinor) {
        return seat(sectionId, "Section", row, number, priceMinor, "EUR", number * 10, 0);
    }

    private static SeatSnapshot snapshot(List<PricedSeat> seats) {
        return new SeatSnapshot(SESSION_ID, NOW, seats, Optional.empty(), Optional.empty(), true);
    }

    private static ValidatedBestSeatsQuery query(int quantity, SeatRankingStrategy strategy) {
        return new ValidatedBestSeatsQuery(quantity, null, null, null, null, null, strategy);
    }

    @Test
    @DisplayName("quantity 0 and 11 are rejected; 1 and 10 are accepted")
    void quantityBounds() {
        SeatSnapshot oneSeat = snapshot(List.of(plainSeat(SECTION_A, "A", 1, 1000)));
        assertThatThrownBy(() -> ranking.rank(oneSeat, query(0, SeatRankingStrategy.BEST_VALUE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ranking.rank(oneSeat, query(11, SeatRankingStrategy.BEST_VALUE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ranking.rank(oneSeat, query(1, SeatRankingStrategy.BEST_VALUE)).status())
                .isEqualTo("OK");
        List<PricedSeat> ten = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            ten.add(plainSeat(SECTION_A, "A", i, 1000));
        }
        assertThat(ranking.rank(snapshot(ten), query(10, SeatRankingStrategy.BEST_VALUE)).status())
                .isEqualTo("OK");
    }

    @Test
    @DisplayName("contiguous same-section same-row consecutive seats rank first and flag true")
    void contiguousWindowPreferred() {
        List<PricedSeat> seats = List.of(
                plainSeat(SECTION_A, "A", 1, 1000),
                plainSeat(SECTION_A, "A", 2, 1000),
                plainSeat(SECTION_A, "A", 4, 1000));

        FindBestSeatsResult result = ranking.rank(snapshot(seats), query(2, SeatRankingStrategy.BEST_VALUE));

        assertThat(result.status()).isEqualTo("OK");
        FindBestSeatsResult.SeatCandidate top = result.candidates().getFirst();
        assertThat(top.contiguous()).isTrue();
        assertThat(top.seats().stream().map(s -> s.seatNumber()).toList()).containsExactly(1, 2);
        assertThat(top.totalPriceMinor()).isEqualTo(2000L);
        assertThat(top.currency()).isEqualTo("EUR");
        assertThat(top.rankingPosition()).isEqualTo(1);
    }

    @Test
    @DisplayName("A1 and A3 are not contiguous for quantity 2")
    void gapBreaksContiguity() {
        List<PricedSeat> seats = List.of(
                plainSeat(SECTION_A, "A", 1, 1000),
                plainSeat(SECTION_A, "A", 3, 1000));

        FindBestSeatsResult result = ranking.rank(snapshot(seats), query(2, SeatRankingStrategy.BEST_VALUE));

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().getFirst().contiguous()).isFalse();
    }

    @Test
    @DisplayName("same seat numbers in different sections are not contiguous")
    void crossSectionBreaksContiguity() {
        List<PricedSeat> seats = List.of(
                plainSeat(SECTION_A, "A", 1, 1000),
                plainSeat(SECTION_B, "A", 2, 1000));

        FindBestSeatsResult result = ranking.rank(snapshot(seats), query(2, SeatRankingStrategy.BEST_VALUE));

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.candidates().getFirst().contiguous()).isFalse();
    }

    @Test
    @DisplayName("same section but different rows are not contiguous")
    void crossRowBreaksContiguity() {
        List<PricedSeat> seats = List.of(
                plainSeat(SECTION_A, "A", 1, 1000),
                plainSeat(SECTION_A, "B", 1, 1000));

        FindBestSeatsResult result = ranking.rank(snapshot(seats), query(2, SeatRankingStrategy.BEST_VALUE));

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.candidates().getFirst().contiguous()).isFalse();
    }

    @Test
    @DisplayName("exact budget boundary is accepted; one minor unit above budget is rejected")
    void budgetBoundary() {
        List<PricedSeat> seats = List.of(
                plainSeat(SECTION_A, "A", 1, 1000),
                plainSeat(SECTION_A, "A", 2, 1000));

        FindBestSeatsResult accepted = ranking.rank(snapshot(seats),
                new ValidatedBestSeatsQuery(2, 2000L, null, null, null, null,
                        SeatRankingStrategy.BEST_VALUE));
        assertThat(accepted.status()).isEqualTo("OK");

        FindBestSeatsResult rejected = ranking.rank(snapshot(seats),
                new ValidatedBestSeatsQuery(2, 1999L, null, null, null, null,
                        SeatRankingStrategy.BEST_VALUE));
        assertThat(rejected.status()).isEqualTo("NO_MATCH");
        assertThat(rejected.candidates()).isEmpty();
        assertThat(rejected.relaxationHints()).isNotEmpty();
    }

    @Test
    @DisplayName("mixed currencies are never summed into one candidate")
    void mixedCurrenciesNeverSummed() {
        List<PricedSeat> seats = List.of(
                seat(SECTION_A, "Section", "A", 1, 1000, "EUR", 10, 0),
                seat(SECTION_A, "Section", "A", 2, 1000, "USD", 20, 0));

        FindBestSeatsResult mixed = ranking.rank(snapshot(seats), query(2, SeatRankingStrategy.BEST_VALUE));
        assertThat(mixed.status()).isEqualTo("NO_MATCH");

        FindBestSeatsResult single = ranking.rank(snapshot(seats), query(1, SeatRankingStrategy.BEST_VALUE));
        assertThat(single.status()).isEqualTo("OK");
        assertThat(single.candidates()).hasSize(2);
    }

    @Test
    @DisplayName("currency filter keeps only matching seats")
    void currencyFilter() {
        List<PricedSeat> seats = List.of(
                seat(SECTION_A, "Section", "A", 1, 1000, "EUR", 10, 0),
                seat(SECTION_A, "Section", "A", 2, 500, "USD", 20, 0));

        FindBestSeatsResult result = ranking.rank(snapshot(seats),
                new ValidatedBestSeatsQuery(1, null, "EUR", null, null, null,
                        SeatRankingStrategy.BEST_VALUE));

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.candidates().getFirst().currency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("identical input snapshots produce equal candidate ordering, regardless of input order")
    void deterministicOrdering() {
        List<PricedSeat> seats = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            seats.add(plainSeat(SECTION_A, "A", i, 1000));
        }
        List<PricedSeat> shuffled = new ArrayList<>(seats);
        Collections.shuffle(shuffled, new java.util.Random(42));

        FindBestSeatsResult first = ranking.rank(snapshot(seats), query(2, SeatRankingStrategy.BEST_VALUE));
        FindBestSeatsResult second = ranking.rank(snapshot(shuffled), query(2, SeatRankingStrategy.BEST_VALUE));

        assertThat(second).isEqualTo(first);
        assertThat(first.status()).isEqualTo("OK");
    }

    @Test
    @DisplayName("stage geometry changes deterministically affect CLOSEST_TO_STAGE ranking")
    void stageGeometryAffectsRanking() {
        PricedSeat near1 = seat(SECTION_A, "Section", "A", 1, 1000, "EUR", 0, 100);
        PricedSeat near2 = seat(SECTION_A, "Section", "A", 2, 1000, "EUR", 0, 110);
        PricedSeat far1 = seat(SECTION_A, "Section", "B", 1, 1000, "EUR", 0, 900);
        PricedSeat far2 = seat(SECTION_A, "Section", "B", 2, 1000, "EUR", 0, 910);
        List<PricedSeat> seats = List.of(near1, near2, far1, far2);

        SeatSnapshot nearStage = new SeatSnapshot(SESSION_ID, NOW, seats,
                Optional.of(new SeatGeometry.Point(BigDecimal.ZERO, BigDecimal.ZERO)),
                Optional.empty(), true);
        SeatSnapshot farStage = new SeatSnapshot(SESSION_ID, NOW, seats,
                Optional.of(new SeatGeometry.Point(BigDecimal.ZERO, BigDecimal.valueOf(1000))),
                Optional.empty(), true);

        FindBestSeatsResult fromNear = ranking.rank(nearStage, query(2, SeatRankingStrategy.CLOSEST_TO_STAGE));
        FindBestSeatsResult fromFar = ranking.rank(farStage, query(2, SeatRankingStrategy.CLOSEST_TO_STAGE));

        assertThat(fromNear.candidates().getFirst().seatIds())
                .containsExactlyInAnyOrder(near1.seatId(), near2.seatId());
        assertThat(fromFar.candidates().getFirst().seatIds())
                .containsExactlyInAnyOrder(far1.seatId(), far2.seatId());
    }

    @Test
    @DisplayName("missing stage geometry does not crash; ranking falls back to neutral criteria")
    void missingGeometryFallsBack() {
        List<PricedSeat> seats = List.of(
                plainSeat(SECTION_A, "A", 1, 1000),
                plainSeat(SECTION_A, "A", 2, 1000));

        FindBestSeatsResult result =
                ranking.rank(snapshot(seats), query(2, SeatRankingStrategy.CLOSEST_TO_STAGE));

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.candidates().getFirst().reasons().toString()).contains("neutral");
    }

    @Test
    @DisplayName("MOST_CENTRAL prefers the window closest to the venue center")
    void mostCentralRanking() {
        List<PricedSeat> seats = List.of(
                seat(SECTION_A, "Section", "A", 1, 1000, "EUR", 0, 0),
                seat(SECTION_A, "Section", "A", 2, 1000, "EUR", 10, 0),
                seat(SECTION_A, "Section", "B", 1, 1000, "EUR", 40, 0),
                seat(SECTION_A, "Section", "B", 2, 1000, "EUR", 50, 0),
                seat(SECTION_A, "Section", "C", 1, 1000, "EUR", 90, 0),
                seat(SECTION_A, "Section", "C", 2, 1000, "EUR", 100, 0));
        SeatSnapshot withCenter = new SeatSnapshot(SESSION_ID, NOW, seats, Optional.empty(),
                Optional.of(new SeatGeometry.Point(BigDecimal.valueOf(45), BigDecimal.ZERO)), true);

        FindBestSeatsResult result = ranking.rank(withCenter, query(2, SeatRankingStrategy.MOST_CENTRAL));

        assertThat(result.candidates().getFirst().seats().stream()
                .map(s -> s.rowLabel()).toList()).containsExactly("B", "B");
    }

    @Test
    @DisplayName("BEST_VALUE prefers the lower total price")
    void bestValuePrefersLowerPrice() {
        List<PricedSeat> seats = List.of(
                seat(SECTION_A, "Cheap", "A", 1, 1000, "EUR", 0, 900),
                seat(SECTION_A, "Cheap", "A", 2, 1000, "EUR", 0, 910),
                seat(SECTION_B, "Expensive", "A", 1, 5000, "EUR", 0, 100),
                seat(SECTION_B, "Expensive", "A", 2, 5000, "EUR", 0, 110));
        SeatSnapshot withStage = new SeatSnapshot(SESSION_ID, NOW, seats,
                Optional.of(new SeatGeometry.Point(BigDecimal.ZERO, BigDecimal.ZERO)),
                Optional.empty(), true);

        FindBestSeatsResult result = ranking.rank(withStage, query(2, SeatRankingStrategy.BEST_VALUE));

        assertThat(result.candidates().getFirst().totalPriceMinor()).isEqualTo(2000L);
    }

    @Test
    @DisplayName("preferred section and category outrank equal alternatives")
    void preferencesOutrank() {
        List<PricedSeat> seats = List.of(
                seat(SECTION_A, " stalls", "A", 1, 1000, "EUR", 10, 0),
                seat(SECTION_A, " stalls", "A", 2, 1000, "EUR", 20, 0),
                seat(SECTION_B, "Balcony", "A", 1, 1000, "EUR", 30, 0),
                seat(SECTION_B, "Balcony", "A", 2, 1000, "EUR", 40, 0));

        FindBestSeatsResult result = ranking.rank(snapshot(seats),
                new ValidatedBestSeatsQuery(2, null, null, SECTION_B, null, null,
                        SeatRankingStrategy.BEST_VALUE));

        assertThat(result.candidates().getFirst().seats().stream()
                .map(s -> s.sectionId()).toList()).containsExactly(SECTION_B, SECTION_B);
    }

    @Test
    @DisplayName("result count is bounded to three candidates")
    void resultCountBounded() {
        List<PricedSeat> seats = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            seats.add(plainSeat(SECTION_A, "A", i, 1000));
        }

        FindBestSeatsResult result = ranking.rank(snapshot(seats), query(2, SeatRankingStrategy.BEST_VALUE));

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.candidates()).hasSize(3);
        assertThat(result.candidates().stream()
                .map(FindBestSeatsResult.SeatCandidate::rankingPosition).toList())
                .containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("empty snapshot returns NO_MATCH with deterministic hints")
    void emptySnapshotNoMatch() {
        FindBestSeatsResult result =
                ranking.rank(snapshot(List.of()), query(2, SeatRankingStrategy.BEST_VALUE));

        assertThat(result.status()).isEqualTo("NO_MATCH");
        assertThat(result.candidates()).isEmpty();
        assertThat(result.relaxationHints()).isNotEmpty();
    }

    @Test
    @DisplayName("ranking reasons never rewrite the contiguity flag")
    void contiguityFlagHonest() {
        List<PricedSeat> seats = List.of(
                plainSeat(SECTION_A, "A", 1, 1000),
                plainSeat(SECTION_A, "A", 3, 1000));

        FindBestSeatsResult result = ranking.rank(snapshot(seats), query(2, SeatRankingStrategy.BEST_VALUE));

        FindBestSeatsResult.SeatCandidate candidate = result.candidates().getFirst();
        assertThat(candidate.contiguous()).isFalse();
        assertThat(candidate.reasons().toString()).contains("non-contiguous");
    }
}
