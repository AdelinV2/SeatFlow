package com.seatflow.ai.service.seat;

import com.seatflow.ai.client.EventServiceClient;
import com.seatflow.ai.client.ReservationAvailabilityClient;
import com.seatflow.ai.client.dto.PricingTierClientDto;
import com.seatflow.ai.client.dto.SeatAvailabilityClientDto;
import com.seatflow.ai.client.dto.SeatMapClientDto;
import com.seatflow.ai.client.dto.SessionBookingContextClientDto;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatCandidateAssemblerTest {

    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    private static final AiRequestContext CONTEXT =
            new AiRequestContext("token", "corr-1", "user-1");
    private static final UUID SESSION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EVENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SECTION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SEAT_1 = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID SEAT_2 = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID SEAT_3 = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID TIER_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    @Mock
    private EventServiceClient eventServiceClient;

    @Mock
    private ReservationAvailabilityClient reservationAvailabilityClient;

    private SimpleMeterRegistry meterRegistry;
    private SeatCandidateAssembler assembler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        assembler = new SeatCandidateAssembler(eventServiceClient, reservationAvailabilityClient,
                meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SessionBookingContextClientDto bookingContext() {
        return new SessionBookingContextClientDto(SESSION_ID, EVENT_ID, "PUBLISHED", "SCHEDULED",
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), null, null, UUID.randomUUID());
    }

    private static PricingTierClientDto tier(String category, String price) {
        return new PricingTierClientDto(TIER_ID, SECTION_ID, category,
                new BigDecimal(price), "EUR");
    }

    private static SeatMapClientDto.SeatMapSeat seat(UUID seatId, String row, int number, boolean active) {
        return new SeatMapClientDto.SeatMapSeat(seatId, row, number, 0, 0, active,
                BigDecimal.valueOf(number * 44L), BigDecimal.ZERO);
    }

    private static SeatMapClientDto.SeatMapSection section(List<SeatMapClientDto.SeatMapSeat> seats,
                                                           List<PricingTierClientDto> tiers) {
        return new SeatMapClientDto.SeatMapSection(SECTION_ID, "Stalls", 1, 10, true,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(440), BigDecimal.valueOf(44),
                BigDecimal.ZERO, 0, seats, tiers);
    }

    private static SeatMapClientDto seatMap(SeatMapClientDto.SeatMapSection... sections) {
        return new SeatMapClientDto(EVENT_ID, UUID.randomUUID(), "Hamlet", "PUBLISHED", "Hall",
                100, 3L, List.of(sections), 1L, List.of());
    }

    private static SeatAvailabilityClientDto availability(SeatAvailabilityClientDto.SeatStatusEntry... entries) {
        return new SeatAvailabilityClientDto(SESSION_ID, EVENT_ID, List.of(entries));
    }

    private void stub(SessionBookingContextClientDto context, SeatMapClientDto map,
                      SeatAvailabilityClientDto availability) {
        when(eventServiceClient.getSessionBookingContext(eq(SESSION_ID), eq(CONTEXT)))
                .thenReturn(context);
        when(eventServiceClient.getSeatMap(eq(EVENT_ID), eq(CONTEXT))).thenReturn(map);
        when(reservationAvailabilityClient.getSeatAvailability(eq(SESSION_ID), eq(CONTEXT)))
                .thenReturn(availability);
    }

    @Test
    @DisplayName("held, sold, and inactive seats never enter the snapshot")
    void excludesUnavailableSeats() {
        stub(bookingContext(),
                seatMap(section(List.of(
                        seat(SEAT_1, "A", 1, true),
                        seat(SEAT_2, "A", 2, true),
                        seat(SEAT_3, "A", 3, false)), List.of(tier("Standard", "25.00")))),
                availability(
                        new SeatAvailabilityClientDto.SeatStatusEntry(SEAT_1, "HELD"),
                        new SeatAvailabilityClientDto.SeatStatusEntry(SEAT_2, "SOLD")));

        SeatCandidateAssembler.AssembledSnapshot assembled =
                assembler.assemble(SESSION_ID, null, null, null, CONTEXT);

        assertThat(assembled.snapshot().seats()).isEmpty();
        assertThat(assembled.snapshot().snapshotAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("available seats resolve the canonical default tier with exact minor units")
    void resolvesDefaultPrice() {
        stub(bookingContext(),
                seatMap(section(List.of(seat(SEAT_1, "A", 1, true)), List.of(tier("Standard", "25.00")))),
                availability());

        SeatCandidateAssembler.AssembledSnapshot assembled =
                assembler.assemble(SESSION_ID, null, null, null, CONTEXT);

        assertThat(assembled.snapshot().seats()).hasSize(1);
        PricedSeat priced = assembled.snapshot().seats().getFirst();
        assertThat(priced.seatId()).isEqualTo(SEAT_1);
        assertThat(priced.pricingTierId()).isEqualTo(TIER_ID);
        assertThat(priced.priceMinor()).isEqualTo(2500L);
        assertThat(priced.currency()).isEqualTo("EUR");
        assertThat(priced.globalPoint()).isPresent();
        assertThat(assembled.selectionHints()).isEmpty();
    }

    @Test
    @DisplayName("availability entries unknown to the seat map are excluded with a bounded metric")
    void unknownAvailabilityEntriesCounted() {
        UUID ghost = UUID.randomUUID();
        stub(bookingContext(),
                seatMap(section(List.of(seat(SEAT_1, "A", 1, true)), List.of(tier("Standard", "25.00")))),
                availability(new SeatAvailabilityClientDto.SeatStatusEntry(ghost, "HELD")));

        SeatCandidateAssembler.AssembledSnapshot assembled =
                assembler.assemble(SESSION_ID, null, null, null, CONTEXT);

        assertThat(assembled.snapshot().seats()).hasSize(1);
        assertThat(meterRegistry.counter("seatflow.ai.seat.mismatch.total", "reason", "unknown-seat")
                .count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("ambiguous section pricing yields a selection hint instead of a silent pick")
    void ambiguousPricingYieldsHint() {
        stub(bookingContext(),
                seatMap(section(List.of(seat(SEAT_1, "A", 1, true)), List.of(
                        new PricingTierClientDto(UUID.randomUUID(), SECTION_ID, "Standard", null, "EUR"),
                        new PricingTierClientDto(UUID.randomUUID(), SECTION_ID, "VIP",
                                BigDecimal.ZERO, "EUR")))),
                availability());

        SeatCandidateAssembler.AssembledSnapshot assembled =
                assembler.assemble(SESSION_ID, null, null, null, CONTEXT);

        assertThat(assembled.snapshot().seats()).isEmpty();
        assertThat(assembled.selectionHints()).hasSize(1);
        assertThat(assembled.selectionHints().getFirst().availableCategories())
                .containsExactlyInAnyOrder("Standard", "VIP");
    }

    @Test
    @DisplayName("requested category resolves matching tiers only")
    void categoryFilter() {
        UUID vipTier = UUID.randomUUID();
        stub(bookingContext(),
                seatMap(section(List.of(seat(SEAT_1, "A", 1, true)), List.of(
                        tier("Standard", "25.00"),
                        new PricingTierClientDto(vipTier, SECTION_ID, "VIP",
                                new BigDecimal("80.00"), "EUR")))),
                availability());

        SeatCandidateAssembler.AssembledSnapshot assembled =
                assembler.assemble(SESSION_ID, null, "vip", null, CONTEXT);

        assertThat(assembled.snapshot().seats()).hasSize(1);
        assertThat(assembled.snapshot().seats().getFirst().pricingTierId()).isEqualTo(vipTier);
        assertThat(assembled.snapshot().seats().getFirst().priceMinor()).isEqualTo(8000L);
    }

    @Test
    @DisplayName("rotated sections disable geometry instead of inventing distances")
    void rotationDisablesGeometry() {
        SeatMapClientDto.SeatMapSection rotated = new SeatMapClientDto.SeatMapSection(
                SECTION_ID, "Rotated", 1, 10, true,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(440), BigDecimal.valueOf(44),
                new BigDecimal("15"), 0,
                List.of(seat(SEAT_1, "A", 1, true)), List.of(tier("Standard", "25.00")));
        stub(bookingContext(), seatMap(rotated), availability());

        SeatCandidateAssembler.AssembledSnapshot assembled =
                assembler.assemble(SESSION_ID, null, null, null, CONTEXT);

        assertThat(assembled.snapshot().geometryReliable()).isFalse();
        assertThat(assembled.snapshot().seats().getFirst().globalPoint()).isEmpty();
        assertThat(assembled.snapshot().stageCenter()).isEmpty();
    }

    @Test
    @DisplayName("primary stage is the lowest zIndex, then stable elementId")
    void primaryStageChoice() {
        UUID first = UUID.fromString("88888888-8888-8888-8888-888888888888");
        UUID second = UUID.fromString("99999999-9999-9999-9999-999999999999");
        SeatMapClientDto map = new SeatMapClientDto(EVENT_ID, UUID.randomUUID(), "Hamlet",
                "PUBLISHED", "Hall", 100, 1L,
                List.of(section(List.of(seat(SEAT_1, "A", 1, true)), List.of(tier("Standard", "25.00")))),
                1L,
                List.of(
                        new SeatMapClientDto.SeatMapLayoutElement(second, "STAGE", "Main",
                                new SeatMapClientDto.SeatMapGeometry(
                                        BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                                        BigDecimal.valueOf(20), BigDecimal.valueOf(10),
                                        BigDecimal.ZERO), 1),
                        new SeatMapClientDto.SeatMapLayoutElement(first, "stage", "Low",
                                new SeatMapClientDto.SeatMapGeometry(
                                        BigDecimal.ZERO, BigDecimal.ZERO,
                                        BigDecimal.valueOf(20), BigDecimal.valueOf(10),
                                        BigDecimal.ZERO), 1)));
        stub(bookingContext(), map, availability());

        SeatCandidateAssembler.AssembledSnapshot assembled =
                assembler.assemble(SESSION_ID, null, null, null, CONTEXT);

        assertThat(assembled.snapshot().stageCenter()).isPresent();
        assertThat(assembled.snapshot().stageCenter().get().x())
                .isEqualByComparingTo(new BigDecimal("10.0000000000"));
    }

    @Test
    @DisplayName("unknown session propagates the invalid-argument error without fabrication")
    void unknownSessionPropagates() {
        when(eventServiceClient.getSessionBookingContext(eq(SESSION_ID), eq(CONTEXT)))
                .thenThrow(new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                        "Unknown event session: " + SESSION_ID));

        assertThatThrownBy(() -> assembler.assemble(SESSION_ID, null, null, null, CONTEXT))
                .isInstanceOf(AiToolException.class)
                .satisfies(ex -> assertThat(((AiToolException) ex).getError())
                        .isEqualTo(AiToolError.INVALID_TOOL_ARGUMENT));
    }

    @Test
    @DisplayName("duplicate seat IDs in the seat map collapse with a bounded metric")
    void duplicateSeatsCollapse() {
        stub(bookingContext(),
                seatMap(section(List.of(
                        seat(SEAT_1, "A", 1, true),
                        seat(SEAT_1, "A", 1, true)), List.of(tier("Standard", "25.00")))),
                availability());

        SeatCandidateAssembler.AssembledSnapshot assembled =
                assembler.assemble(SESSION_ID, null, null, null, CONTEXT);

        assertThat(assembled.snapshot().seats()).hasSize(1);
        assertThat(meterRegistry.counter("seatflow.ai.seat.mismatch.total", "reason", "duplicate-seat")
                .count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("sections without an identifier are excluded safely (REV-001)")
    void unidentifiedSectionsExcluded() {
        SeatMapClientDto.SeatMapSection unidentified =
                new SeatMapClientDto.SeatMapSection(null, "Ghost", 1, 10, true,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(440),
                        BigDecimal.valueOf(44), BigDecimal.ZERO, 0,
                        List.of(seat(SEAT_1, "A", 1, true)), List.of(tier("Standard", "25.00")));
        stub(bookingContext(), seatMap(unidentified), availability());

        SeatCandidateAssembler.AssembledSnapshot assembled =
                assembler.assemble(SESSION_ID, null, null, null, CONTEXT);

        assertThat(assembled.snapshot().seats()).isEmpty();
        assertThat(meterRegistry.counter("seatflow.ai.seat.mismatch.total", "reason",
                        "unidentified-section").count()).isEqualTo(1.0);
    }
}
