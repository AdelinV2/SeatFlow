package com.seatflow.seatmap.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.seatmap.mapper.SeatMapper;
import com.seatflow.seatmap.model.entity.Seat;
import com.seatflow.seatmap.model.entity.Venue;
import com.seatflow.seatmap.model.entity.VenueLayoutElement;
import com.seatflow.seatmap.model.entity.VenueSection;
import com.seatflow.seatmap.model.enums.LayoutElementType;
import com.seatflow.seatmap.repository.SeatRepository;
import com.seatflow.seatmap.repository.OutboxEventRepository;
import com.seatflow.seatmap.repository.VenueLayoutElementRepository;
import com.seatflow.seatmap.repository.VenueRepository;
import com.seatflow.seatmap.repository.VenueSectionRepository;
import com.seatflow.seatmap.service.impl.VenueLayoutServiceImpl;
import com.seatflow.seatmap.web.dto.request.SaveVenueLayoutRequest;
import com.seatflow.seatmap.web.dto.response.SeatResponse;
import com.seatflow.seatmap.web.dto.response.VenueSeatMapLayoutResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VenueLayoutServiceImplTest {

    @Mock
    private VenueRepository venueRepository;
    @Mock
    private VenueSectionRepository sectionRepository;
    @Mock
    private SeatRepository seatRepository;
    @Mock
    private VenueLayoutElementRepository elementRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private LayoutValidationService validationService;
    @Mock
    private SeatMapper seatMapper;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private VenueLayoutServiceImpl layoutService;

    @BeforeEach
    void setUp() {
        layoutService = new VenueLayoutServiceImpl(
                venueRepository, sectionRepository, seatRepository,
                elementRepository, outboxEventRepository, validationService, seatMapper, objectMapper, null);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Venue venueWithVersion(UUID id, long layoutVersion, int capacity) {
        return Venue.builder().id(id).name("Hall").address("1 St").city("NYC").country("USA")
                .capacity(capacity).layoutVersion(layoutVersion).build();
    }

    private static VenueSection sectionEntity(UUID id, Venue venue, String name) {
        return VenueSection.builder().id(id).venue(venue).name(name)
                .rowCount(2).colCount(2).isActive(true)
                .positionX(new BigDecimal("0.000")).positionY(new BigDecimal("0.000"))
                .width(new BigDecimal("88.000")).height(new BigDecimal("88.000"))
                .rotationDeg(BigDecimal.ZERO).zIndex(0)
                .build();
    }

    private static Seat seatEntity(UUID id, VenueSection section, String row, int num, boolean active) {
        return Seat.builder().id(id).section(section).rowLabel(row).seatNumber(num)
                .gridX(num - 1).gridY(0).isActive(active)
                .positionX(new BigDecimal("0.000")).positionY(new BigDecimal("0.000"))
                .build();
    }

    private static SaveVenueLayoutRequest.SeatUpsert seatUpsert(UUID seatId, String row, int num, boolean active) {
        int rowIdx = row.charAt(0) - 'A';
        int colIdx = num - 1;
        return new SaveVenueLayoutRequest.SeatUpsert(seatId, row, num, colIdx, rowIdx,
                new BigDecimal(colIdx * 44L + ".000"), new BigDecimal(rowIdx * 44L + ".000"), active);
    }

    private static SaveVenueLayoutRequest.SectionUpsert sectionUpsert(UUID sectionId, String name,
                                                                      List<SaveVenueLayoutRequest.SeatUpsert> seats) {
        return new SaveVenueLayoutRequest.SectionUpsert(sectionId, name, 2, 2, true,
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("88"), new BigDecimal("88"),
                BigDecimal.ZERO, 0, null, seats);
    }

    private static SaveVenueLayoutRequest.Geometry geometry() {
        return new SaveVenueLayoutRequest.Geometry(
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("100"), new BigDecimal("40"), BigDecimal.ZERO);
    }

    private void stubEmptyReads(Venue venue) {
        when(sectionRepository.findByVenueIdOrderByZIndexAscNameAsc(venue.getId())).thenReturn(List.of());
        when(elementRepository.findByVenueIdOrderByZIndexAscIdAsc(venue.getId())).thenReturn(List.of());
    }

    private void stubSeatMapper() {
        when(seatMapper.toResponse(any(Seat.class))).thenAnswer(inv -> {
            Seat s = inv.getArgument(0);
            return new SeatResponse(s.getId(), s.getRowLabel(), s.getSeatNumber(),
                    s.getGridX(), s.getGridY(), s.getIsActive(),
                    s.getPositionX(), s.getPositionY());
        });
    }

    // ------------------------------------------------------------------
    // getEditableLayout
    // ------------------------------------------------------------------

    @Test
    void shouldReturnEditableLayoutIncludingInactiveRows() {
        UUID venueId = UUID.randomUUID();
        Venue venue = venueWithVersion(venueId, 7L, 100);
        VenueSection active = sectionEntity(UUID.randomUUID(), venue, "Active");
        VenueSection inactive = sectionEntity(UUID.randomUUID(), venue, "Inactive");
        inactive.setIsActive(false);
        Seat s1 = seatEntity(UUID.randomUUID(), active, "A", 1, true);
        Seat s2 = seatEntity(UUID.randomUUID(), active, "A", 2, false);

        when(venueRepository.findById(venueId)).thenReturn(Optional.of(venue));
        when(sectionRepository.findByVenueIdOrderByZIndexAscNameAsc(venueId))
                .thenReturn(List.of(active, inactive));
        when(seatRepository.findBySectionIdForEditor(active.getId())).thenReturn(List.of(s1, s2));
        when(seatRepository.findBySectionIdForEditor(inactive.getId())).thenReturn(List.of());
        when(elementRepository.findByVenueIdOrderByZIndexAscIdAsc(venueId)).thenReturn(List.of());
        stubSeatMapper();

        VenueSeatMapLayoutResponse response = layoutService.getEditableLayout(venueId);

        assertThat(response.sections()).hasSize(2);
        assertThat(response.layoutVersion()).isEqualTo(7L);
        assertThat(response.totalConfiguredSeats()).isEqualTo(1L);
    }

    @Test
    void shouldThrowNotFoundForUnknownVenueOnRead() {
        UUID venueId = UUID.randomUUID();
        when(venueRepository.findById(venueId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> layoutService.getEditableLayout(venueId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // validateLayout
    // ------------------------------------------------------------------

    @Test
    void shouldValidateWithoutWriting() {
        UUID venueId = UUID.randomUUID();
        Venue venue = venueWithVersion(venueId, 3L, 50);
        SaveVenueLayoutRequest request = new SaveVenueLayoutRequest(3L, List.of(), List.of());

        when(venueRepository.findById(venueId)).thenReturn(Optional.of(venue));
        when(sectionRepository.findByVenueIdOrderByZIndexAscNameAsc(venueId)).thenReturn(List.of());
        when(elementRepository.findByVenueIdOrderByZIndexAscIdAsc(venueId)).thenReturn(List.of());

        layoutService.validateLayout(venueId, request);

        verify(validationService).validate(any(Venue.class), any(SaveVenueLayoutRequest.class), any());
        verify(sectionRepository, never()).save(any());
        verify(seatRepository, never()).save(any());
        assertThat(venue.getLayoutVersion()).isEqualTo(3L);
    }

    @Test
    void shouldThrowNotFoundForUnknownVenueOnValidate() {
        UUID venueId = UUID.randomUUID();
        when(venueRepository.findById(venueId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> layoutService.validateLayout(venueId,
                new SaveVenueLayoutRequest(0L, List.of(), List.of())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // saveLayout: version conflict
    // ------------------------------------------------------------------

    @Test
    void shouldRejectStaleVersionWithConflictAndNoWrites() {
        UUID venueId = UUID.randomUUID();
        Venue venue = venueWithVersion(venueId, 8L, 100);
        when(venueRepository.findByIdForLayoutUpdate(venueId)).thenReturn(Optional.of(venue));

        SaveVenueLayoutRequest request = new SaveVenueLayoutRequest(7L, List.of(), List.of());

        assertThatThrownBy(() -> layoutService.saveLayout(venueId, request))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CONFLICT))
                .hasMessageContaining("Layout version");
        assertThat(venue.getLayoutVersion()).isEqualTo(8L);
        verify(validationService, never()).validate(any(), any(), any());
        verify(sectionRepository, never()).save(any());
        verify(seatRepository, never()).save(any());
    }

    @Test
    void shouldThrowNotFoundForUnknownVenueOnSave() {
        UUID venueId = UUID.randomUUID();
        when(venueRepository.findByIdForLayoutUpdate(venueId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> layoutService.saveLayout(venueId,
                new SaveVenueLayoutRequest(0L, List.of(), List.of())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // saveLayout: happy path, stable IDs, version bump once
    // ------------------------------------------------------------------

    @Test
    void shouldUpdateMatchedIdsInPlaceAndBumpVersionOnce() {
        UUID venueId = UUID.randomUUID();
        Venue venue = venueWithVersion(venueId, 7L, 100);
        UUID sectionId = UUID.randomUUID();
        VenueSection section = sectionEntity(sectionId, venue, "Orchestra");
        UUID seatId = UUID.randomUUID();
        Seat seat = seatEntity(seatId, section, "A", 1, true);

        when(venueRepository.findByIdForLayoutUpdate(venueId)).thenReturn(Optional.of(venue));
        when(sectionRepository.findByVenueIdOrderByZIndexAscNameAsc(venueId))
                .thenReturn(List.of(section));
        when(seatRepository.findBySectionIdForEditor(sectionId)).thenReturn(List.of(seat));
        when(elementRepository.findByVenueIdOrderByZIndexAscIdAsc(venueId)).thenReturn(List.of());
        stubSeatMapper();

        SaveVenueLayoutRequest request = new SaveVenueLayoutRequest(7L,
                List.of(sectionUpsert(sectionId, "Renamed", List.of(seatUpsert(seatId, "B", 2, false)))),
                List.of());

        VenueSeatMapLayoutResponse response = layoutService.saveLayout(venueId, request);

        // Existing entities updated in place, never replaced.
        assertThat(section.getId()).isEqualTo(sectionId);
        assertThat(section.getName()).isEqualTo("Renamed");
        assertThat(seat.getId()).isEqualTo(seatId);
        assertThat(seat.getRowLabel()).isEqualTo("B");
        assertThat(seat.getSeatNumber()).isEqualTo(2);
        assertThat(seat.getIsActive()).isFalse();
        // No new rows created for matched IDs.
        verify(sectionRepository, never()).save(any(VenueSection.class));
        verify(seatRepository, never()).save(any(Seat.class));
        // Version incremented exactly once.
        assertThat(venue.getLayoutVersion()).isEqualTo(8L);
        assertThat(response.layoutVersion()).isEqualTo(8L);
        verify(venueRepository).save(venue);
    }

    @Test
    void shouldCreateOnlyNullIdsAndDeactivateOmittedRows() {
        UUID venueId = UUID.randomUUID();
        Venue venue = venueWithVersion(venueId, 5L, 100);
        UUID keptSectionId = UUID.randomUUID();
        UUID omittedSectionId = UUID.randomUUID();
        VenueSection kept = sectionEntity(keptSectionId, venue, "Kept");
        VenueSection omitted = sectionEntity(omittedSectionId, venue, "Omitted");
        UUID keptSeatId = UUID.randomUUID();
        UUID omittedSeatId = UUID.randomUUID();
        Seat keptSeat = seatEntity(keptSeatId, kept, "A", 1, true);
        Seat omittedSeat = seatEntity(omittedSeatId, kept, "A", 2, true);
        Seat seatInOmittedSection = seatEntity(UUID.randomUUID(), omitted, "A", 1, true);

        when(venueRepository.findByIdForLayoutUpdate(venueId)).thenReturn(Optional.of(venue));
        when(sectionRepository.findByVenueIdOrderByZIndexAscNameAsc(venueId))
                .thenReturn(List.of(kept, omitted));
        when(seatRepository.findBySectionIdForEditor(keptSectionId))
                .thenReturn(new java.util.ArrayList<>(List.of(keptSeat, omittedSeat)));
        when(seatRepository.findBySectionIdForEditor(omittedSectionId))
                .thenReturn(new java.util.ArrayList<>(List.of(seatInOmittedSection)));
        when(elementRepository.findByVenueIdOrderByZIndexAscIdAsc(venueId)).thenReturn(List.of());
        when(sectionRepository.save(any(VenueSection.class))).thenAnswer(inv -> {
            VenueSection s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(UUID.randomUUID());
            }
            return s;
        });
        when(seatRepository.save(any(Seat.class))).thenAnswer(inv -> {
            Seat s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(UUID.randomUUID());
            }
            return s;
        });
        stubSeatMapper();

        SaveVenueLayoutRequest request = new SaveVenueLayoutRequest(5L,
                List.of(
                        sectionUpsert(keptSectionId, "Kept",
                                List.of(
                                        seatUpsert(keptSeatId, "A", 1, true),
                                        // new seat with null ID
                                        seatUpsert(null, "B", 1, true))),
                        // new section with null ID and one new seat
                        sectionUpsert(null, "New",
                                List.of(seatUpsert(null, "A", 1, true)))),
                List.of());

        layoutService.saveLayout(venueId, request);

        // Omitted section deactivated, never deleted.
        assertThat(omitted.getIsActive()).isFalse();
        assertThat(seatInOmittedSection.getIsActive()).isFalse();
        verify(sectionRepository, never()).delete(any());
        verify(seatRepository, never()).delete(any());
        // Omitted seat in kept section deactivated, row retained.
        assertThat(omittedSeat.getIsActive()).isFalse();
        assertThat(omittedSeat.getId()).isEqualTo(omittedSeatId);
        // Null IDs created exactly twice for seats in kept section + new section path.
        ArgumentCaptor<Seat> seatCaptor = ArgumentCaptor.forClass(Seat.class);
        verify(seatRepository, org.mockito.Mockito.times(2)).save(seatCaptor.capture());
        assertThat(seatCaptor.getAllValues()).allSatisfy(s -> assertThat(s.getId()).isNotNull());
        assertThat(venue.getLayoutVersion()).isEqualTo(6L);
    }

    @Test
    void shouldReplaceOmittedElementsWithinTransaction() {
        UUID venueId = UUID.randomUUID();
        Venue venue = venueWithVersion(venueId, 2L, 100);
        VenueLayoutElement kept;
        VenueLayoutElement omitted;
        try {
            kept = VenueLayoutElement.builder().id(UUID.randomUUID()).venue(venue)
                    .type(LayoutElementType.STAGE)
                    .geometry(objectMapper.readTree("{\"x\":0,\"y\":0,\"width\":10,\"height\":10,\"rotationDeg\":0}"))
                    .zIndex(0).build();
            omitted = VenueLayoutElement.builder().id(UUID.randomUUID()).venue(venue)
                    .type(LayoutElementType.AISLE)
                    .geometry(objectMapper.readTree("{\"x\":1,\"y\":1,\"width\":5,\"height\":5,\"rotationDeg\":0}"))
                    .zIndex(1).build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        when(venueRepository.findByIdForLayoutUpdate(venueId)).thenReturn(Optional.of(venue));
        when(sectionRepository.findByVenueIdOrderByZIndexAscNameAsc(venueId)).thenReturn(List.of());
        when(elementRepository.findByVenueIdOrderByZIndexAscIdAsc(venueId))
                .thenReturn(new java.util.ArrayList<>(List.of(kept, omitted)));

        SaveVenueLayoutRequest request = new SaveVenueLayoutRequest(2L, List.of(),
                List.of(new SaveVenueLayoutRequest.LayoutElementUpsert(
                        kept.getId(), LayoutElementType.LABEL, "Main", geometry(), 3)));

        layoutService.saveLayout(venueId, request);

        assertThat(kept.getType()).isEqualTo(LayoutElementType.LABEL);
        assertThat(kept.getLabel()).isEqualTo("Main");
        assertThat(kept.getZIndex()).isEqualTo(3);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VenueLayoutElement>> deleteCaptor = ArgumentCaptor.forClass(List.class);
        verify(elementRepository).deleteAll(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue()).extracting(VenueLayoutElement::getId)
                .containsExactly(omitted.getId());
        assertThat(venue.getLayoutVersion()).isEqualTo(3L);
    }

    // ------------------------------------------------------------------
    // saveLayout: failure paths leave version unchanged
    // ------------------------------------------------------------------

    @Test
    void shouldNotBumpVersionWhenValidationFails() {
        UUID venueId = UUID.randomUUID();
        Venue venue = venueWithVersion(venueId, 4L, 100);
        when(venueRepository.findByIdForLayoutUpdate(venueId)).thenReturn(Optional.of(venue));
        when(sectionRepository.findByVenueIdOrderByZIndexAscNameAsc(venueId)).thenReturn(List.of());
        when(elementRepository.findByVenueIdOrderByZIndexAscIdAsc(venueId)).thenReturn(List.of());

        SaveVenueLayoutRequest request = new SaveVenueLayoutRequest(4L, List.of(), List.of());
        org.mockito.Mockito.doThrow(new ValidationException("bad", ErrorCode.INVALID_REQUEST))
                .when(validationService).validate(any(), any(), any());

        assertThatThrownBy(() -> layoutService.saveLayout(venueId, request))
                .isInstanceOf(ValidationException.class);
        assertThat(venue.getLayoutVersion()).isEqualTo(4L);
        verify(sectionRepository, never()).save(any());
        verify(elementRepository, never()).deleteAll(any());
    }

    @Test
    void shouldRejectCapacityOverflowWithoutVersionBump() {
        UUID venueId = UUID.randomUUID();
        Venue venue = venueWithVersion(venueId, 1L, 1);
        UUID sectionId = UUID.randomUUID();
        VenueSection section = sectionEntity(sectionId, venue, "S");

        when(venueRepository.findByIdForLayoutUpdate(venueId)).thenReturn(Optional.of(venue));
        when(sectionRepository.findByVenueIdOrderByZIndexAscNameAsc(venueId))
                .thenReturn(List.of(section));
        when(seatRepository.findBySectionIdForEditor(sectionId)).thenReturn(List.of());
        when(elementRepository.findByVenueIdOrderByZIndexAscIdAsc(venueId)).thenReturn(List.of());
        when(seatRepository.save(any(Seat.class))).thenAnswer(inv -> {
            Seat s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        // Two active seats against capacity 1; validation mock passes so the
        // post-mutation capacity guard must reject.
        SaveVenueLayoutRequest request = new SaveVenueLayoutRequest(1L,
                List.of(sectionUpsert(sectionId, "S",
                        List.of(seatUpsert(null, "A", 1, true), seatUpsert(null, "A", 2, true)))),
                List.of());

        assertThatThrownBy(() -> layoutService.saveLayout(venueId, request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("capacity");
        assertThat(venue.getLayoutVersion()).isEqualTo(1L);
    }

    @Test
    void shouldIncrementVersionOnceOnNoOpSave() {
        UUID venueId = UUID.randomUUID();
        Venue venue = venueWithVersion(venueId, 9L, 100);
        UUID sectionId = UUID.randomUUID();
        VenueSection section = sectionEntity(sectionId, venue, "S");
        UUID seatId = UUID.randomUUID();
        Seat seat = seatEntity(seatId, section, "A", 1, true);

        when(venueRepository.findByIdForLayoutUpdate(venueId)).thenReturn(Optional.of(venue));
        when(sectionRepository.findByVenueIdOrderByZIndexAscNameAsc(venueId))
                .thenReturn(List.of(section));
        when(seatRepository.findBySectionIdForEditor(sectionId)).thenReturn(List.of(seat));
        when(elementRepository.findByVenueIdOrderByZIndexAscIdAsc(venueId)).thenReturn(List.of());
        stubSeatMapper();

        SaveVenueLayoutRequest request = new SaveVenueLayoutRequest(9L,
                List.of(sectionUpsert(sectionId, "S", List.of(seatUpsert(seatId, "A", 1, true)))),
                List.of());

        VenueSeatMapLayoutResponse response = layoutService.saveLayout(venueId, request);

        assertThat(venue.getLayoutVersion()).isEqualTo(10L);
        assertThat(response.layoutVersion()).isEqualTo(10L);
    }
}
