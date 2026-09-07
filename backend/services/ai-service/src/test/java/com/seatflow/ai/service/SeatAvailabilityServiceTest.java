package com.seatflow.ai.service;

import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;
import com.seatflow.ai.service.impl.SeatAvailabilityServiceImpl;
import com.seatflow.ai.service.seat.PricedSeat;
import com.seatflow.ai.service.seat.SeatCandidateAssembler;
import com.seatflow.ai.service.seat.SeatRankingService;
import com.seatflow.ai.service.seat.SeatSnapshot;
import com.seatflow.ai.tool.dto.AvailableSeatsResult;
import com.seatflow.ai.tool.dto.FindBestSeatsRequest;
import com.seatflow.ai.tool.dto.FindBestSeatsResult;
import com.seatflow.ai.tool.dto.GetAvailableSeatsRequest;
import com.seatflow.ai.tool.dto.SeatRankingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatAvailabilityServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    private static final AiRequestContext CONTEXT =
            new AiRequestContext("token", "corr-1", "user-1");
    private static final AiRequestContext ANONYMOUS =
            new AiRequestContext(null, "corr-1", null);
    private static final UUID SESSION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private SeatCandidateAssembler assembler;

    @Mock
    private SeatRankingService rankingService;

    private SeatAvailabilityService service;

    @BeforeEach
    void setUp() {
        service = new SeatAvailabilityServiceImpl(assembler, rankingService);
        org.mockito.Mockito.lenient().when(assembler.toDisplayItem(any())).thenCallRealMethod();
    }

    private static PricedSeat priced(UUID seatId, long priceMinor) {
        return new PricedSeat(seatId, UUID.randomUUID(), "Stalls", "A", 1, Optional.empty(),
                "Standard", UUID.randomUUID(), priceMinor, "EUR");
    }

    private static SeatCandidateAssembler.AssembledSnapshot assembledWith(PricedSeat... seats) {
        SeatSnapshot snapshot = new SeatSnapshot(SESSION_ID, NOW, List.of(seats),
                Optional.empty(), Optional.empty(), false);
        return new SeatCandidateAssembler.AssembledSnapshot(snapshot, List.of());
    }

    @Test
    @DisplayName("anonymous callers are rejected before any downstream call")
    void rejectsAnonymous() {
        assertThatThrownBy(() -> service.getAvailableSeats(
                new GetAvailableSeatsRequest(SESSION_ID.toString(), null, null, null, null, null),
                ANONYMOUS))
                .isInstanceOf(AiToolException.class)
                .satisfies(ex -> assertThat(((AiToolException) ex).getError())
                        .isEqualTo(AiToolError.UNAUTHENTICATED));
        assertThatThrownBy(() -> service.findBestSeats(
                new FindBestSeatsRequest(SESSION_ID.toString(), 2, null, null, null, null, null,
                        SeatRankingStrategy.BEST_VALUE),
                ANONYMOUS))
                .isInstanceOf(AiToolException.class);
    }

    @Test
    @DisplayName("malformed session UUID is rejected before downstream calls")
    void rejectsMalformedUuid() {
        assertThatThrownBy(() -> service.getAvailableSeats(
                new GetAvailableSeatsRequest("not-a-uuid", null, null, null, null, null), CONTEXT))
                .isInstanceOf(AiToolException.class)
                .satisfies(ex -> assertThat(((AiToolException) ex).getError())
                        .isEqualTo(AiToolError.INVALID_TOOL_ARGUMENT));
        verify(assembler, never()).assemble(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("quantity 0 and 11 are rejected; 1 and 10 are accepted without silent reduction")
    void quantityBounds() {
        FindBestSeatsRequest zero = new FindBestSeatsRequest(SESSION_ID.toString(), 0, null, null,
                null, null, null, SeatRankingStrategy.BEST_VALUE);
        FindBestSeatsRequest eleven = new FindBestSeatsRequest(SESSION_ID.toString(), 11, null, null,
                null, null, null, SeatRankingStrategy.BEST_VALUE);
        assertThatThrownBy(() -> service.findBestSeats(zero, CONTEXT))
                .isInstanceOf(AiToolException.class);
        assertThatThrownBy(() -> service.findBestSeats(eleven, CONTEXT))
                .isInstanceOf(AiToolException.class);

        when(assembler.assemble(eq(SESSION_ID), eq(null), eq(null), eq(null), eq(CONTEXT)))
                .thenReturn(assembledWith(priced(UUID.randomUUID(), 1000)));
        when(rankingService.rank(any(), any())).thenReturn(
                new FindBestSeatsResult(SESSION_ID, NOW, "OK", List.of(), List.of(), List.of()));

        for (int quantity : List.of(1, 10)) {
            service.findBestSeats(new FindBestSeatsRequest(SESSION_ID.toString(), quantity, null,
                    null, null, null, null, SeatRankingStrategy.BEST_VALUE), CONTEXT);
        }
        ArgumentCaptor<SeatRankingService.ValidatedBestSeatsQuery> queries =
                ArgumentCaptor.forClass(SeatRankingService.ValidatedBestSeatsQuery.class);
        verify(rankingService, org.mockito.Mockito.times(2)).rank(any(), queries.capture());
        assertThat(queries.getAllValues().stream()
                .map(SeatRankingService.ValidatedBestSeatsQuery::quantity).toList())
                .containsExactly(1, 10);
    }

    @Test
    @DisplayName("missing strategy is rejected with the valid choices named")
    void strategyRequired() {
        assertThatThrownBy(() -> service.findBestSeats(
                new FindBestSeatsRequest(SESSION_ID.toString(), 2, null, null, null, null, null, null),
                CONTEXT))
                .isInstanceOf(AiToolException.class)
                .hasMessageContaining("CLOSEST_TO_STAGE");
    }

    @Test
    @DisplayName("result limit is bounded; per-seat budget filters candidates")
    void limitAndBudget() {
        List<PricedSeat> seats = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            seats.add(priced(UUID.randomUUID(), i < 30 ? 1000 : 5000));
        }
        SeatSnapshot snapshot = new SeatSnapshot(SESSION_ID, NOW, seats,
                Optional.empty(), Optional.empty(), false);
        when(assembler.assemble(eq(SESSION_ID), eq(null), eq(null), eq(null), eq(CONTEXT)))
                .thenReturn(new SeatCandidateAssembler.AssembledSnapshot(snapshot, List.of()));

        AvailableSeatsResult capped = service.getAvailableSeats(
                new GetAvailableSeatsRequest(SESSION_ID.toString(), null, null, null, null, 100),
                CONTEXT);
        assertThat(capped.seats()).hasSize(50);

        AvailableSeatsResult budgeted = service.getAvailableSeats(
                new GetAvailableSeatsRequest(SESSION_ID.toString(), null, null, 2000L, null, null),
                CONTEXT);
        assertThat(budgeted.seats()).hasSize(20);
        assertThat(budgeted.currency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("currency is normalized; malformed codes are rejected")
    void currencyNormalization() {
        when(assembler.assemble(eq(SESSION_ID), eq(null), eq(null), eq("EUR"), eq(CONTEXT)))
                .thenReturn(assembledWith());

        service.getAvailableSeats(
                new GetAvailableSeatsRequest(SESSION_ID.toString(), null, null, null, "eur", null),
                CONTEXT);
        verify(assembler).assemble(eq(SESSION_ID), eq(null), eq(null), eq("EUR"), eq(CONTEXT));

        assertThatThrownBy(() -> service.getAvailableSeats(
                new GetAvailableSeatsRequest(SESSION_ID.toString(), null, null, null, "EURO", null),
                CONTEXT))
                .isInstanceOf(AiToolException.class);
    }

    @Test
    @DisplayName("empty priced snapshot with selection hints returns PRICING_SELECTION_REQUIRED")
    void pricingSelectionRequired() {
        AvailableSeatsResult.PricingSelectionHint hint =
                new AvailableSeatsResult.PricingSelectionHint(
                        UUID.randomUUID(), "Stalls", List.of("Standard", "VIP"));
        SeatSnapshot snapshot = new SeatSnapshot(SESSION_ID, NOW, List.of(),
                Optional.empty(), Optional.empty(), false);
        when(assembler.assemble(eq(SESSION_ID), eq(null), eq(null), eq(null), eq(CONTEXT)))
                .thenReturn(new SeatCandidateAssembler.AssembledSnapshot(snapshot, List.of(hint)));

        FindBestSeatsResult result = service.findBestSeats(
                new FindBestSeatsRequest(SESSION_ID.toString(), 2, null, null, null, null, null,
                        SeatRankingStrategy.BEST_VALUE),
                CONTEXT);

        assertThat(result.status()).isEqualTo("PRICING_SELECTION_REQUIRED");
        assertThat(result.pricingSelectionRequired()).hasSize(1);
        verify(rankingService, never()).rank(any(), any());
    }

    @Test
    @DisplayName("pricing hints pass through on OK results instead of being dropped (REV-002)")
    void pricingHintsPassThroughOnOk() {
        AvailableSeatsResult.PricingSelectionHint hint =
                new AvailableSeatsResult.PricingSelectionHint(
                        UUID.randomUUID(), "Balcony", List.of("VIP", "Standard"));
        SeatSnapshot snapshot = new SeatSnapshot(SESSION_ID, NOW,
                List.of(priced(UUID.randomUUID(), 1000), priced(UUID.randomUUID(), 1000)),
                Optional.empty(), Optional.empty(), false);
        when(assembler.assemble(eq(SESSION_ID), eq(null), eq(null), eq(null), eq(CONTEXT)))
                .thenReturn(new SeatCandidateAssembler.AssembledSnapshot(snapshot, List.of(hint)));
        FindBestSeatsResult ranked =
                new FindBestSeatsResult(SESSION_ID, NOW, "OK", List.of(), List.of(), List.of());
        when(rankingService.rank(eq(snapshot), any())).thenReturn(ranked);

        FindBestSeatsResult result = service.findBestSeats(
                new FindBestSeatsRequest(SESSION_ID.toString(), 2, null, null, null, null, null,
                        SeatRankingStrategy.BEST_VALUE),
                CONTEXT);

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.pricingSelectionRequired()).hasSize(1);
    }
}
