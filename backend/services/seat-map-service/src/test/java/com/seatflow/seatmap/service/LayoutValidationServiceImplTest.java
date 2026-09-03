package com.seatflow.seatmap.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.seatmap.model.entity.Venue;
import com.seatflow.seatmap.model.enums.LayoutElementType;
import com.seatflow.seatmap.service.impl.LayoutValidationServiceImpl;
import com.seatflow.seatmap.web.dto.request.SaveVenueLayoutRequest;
import com.seatflow.seatmap.web.dto.response.LayoutElementResponse;
import com.seatflow.seatmap.web.dto.response.SeatResponse;
import com.seatflow.seatmap.web.dto.response.SectionLayoutResponse;
import com.seatflow.seatmap.web.dto.response.VenueSeatMapLayoutResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LayoutValidationServiceImplTest {

    private LayoutValidationServiceImpl validationService;
    private Venue venue;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        validationService = new LayoutValidationServiceImpl();
        venue = Venue.builder()
                .id(UUID.randomUUID())
                .name("Grand Theatre")
                .capacity(100)
                .layoutVersion(7L)
                .build();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private SaveVenueLayoutRequest.SeatUpsert seat(String rowLabel, int seatNumber,
                                                   int gridX, int gridY,
                                                   String posX, String posY, boolean active) {
        return new SaveVenueLayoutRequest.SeatUpsert(
                null, rowLabel, seatNumber, gridX, gridY,
                new BigDecimal(posX), new BigDecimal(posY), active);
    }

    private SaveVenueLayoutRequest.SeatUpsert seatWithId(UUID seatId, String rowLabel, int seatNumber,
                                                         int gridX, int gridY,
                                                         String posX, String posY, boolean active) {
        return new SaveVenueLayoutRequest.SeatUpsert(
                seatId, rowLabel, seatNumber, gridX, gridY,
                new BigDecimal(posX), new BigDecimal(posY), active);
    }

    private SaveVenueLayoutRequest.SectionUpsert section(String name, UUID sectionId,
                                                         int rowCount, int colCount,
                                                         String width, String height,
                                                         List<SaveVenueLayoutRequest.SeatUpsert> seats) {
        return new SaveVenueLayoutRequest.SectionUpsert(
                sectionId, name, rowCount, colCount, true,
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal(width), new BigDecimal(height),
                BigDecimal.ZERO, 0, null, seats);
    }

    private SaveVenueLayoutRequest.SectionUpsert sectionFull(String name, UUID sectionId,
                                                             boolean active,
                                                             String posX, String posY,
                                                             String width, String height,
                                                             String rotation, int zIndex,
                                                             JsonNode shapeMetadata,
                                                             int rowCount, int colCount,
                                                             List<SaveVenueLayoutRequest.SeatUpsert> seats) {
        return new SaveVenueLayoutRequest.SectionUpsert(
                sectionId, name, rowCount, colCount, active,
                new BigDecimal(posX), new BigDecimal(posY),
                new BigDecimal(width), new BigDecimal(height),
                new BigDecimal(rotation), zIndex, shapeMetadata, seats);
    }

    private SaveVenueLayoutRequest.Geometry geometry(String x, String y, String w, String h, String rot) {
        return new SaveVenueLayoutRequest.Geometry(
                new BigDecimal(x), new BigDecimal(y),
                new BigDecimal(w), new BigDecimal(h), new BigDecimal(rot));
    }

    private SaveVenueLayoutRequest.LayoutElementUpsert element(UUID elementId, LayoutElementType type,
                                                                String label,
                                                                SaveVenueLayoutRequest.Geometry geometry,
                                                                int zIndex) {
        return new SaveVenueLayoutRequest.LayoutElementUpsert(elementId, type, label, geometry, zIndex);
    }

    private SaveVenueLayoutRequest request(List<SaveVenueLayoutRequest.SectionUpsert> sections,
                                            List<SaveVenueLayoutRequest.LayoutElementUpsert> elements) {
        return new SaveVenueLayoutRequest(7L, sections, elements);
    }

    private LayoutValidationService.ExistingLayoutIds emptyIds() {
        return LayoutValidationService.ExistingLayoutIds.empty();
    }

    private void assertInvalid(SaveVenueLayoutRequest req, LayoutValidationService.ExistingLayoutIds ids) {
        assertThatThrownBy(() -> validationService.validate(venue, req, ids))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
    }



    // ------------------------------------------------------------------
    // Rule 1: trim / blank-after-trim
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Rule 1: blank-after-trim section name is rejected")
    void rule1_blankSectionNameRejected() {
        var sections = List.of(section("   ", null, 2, 2, "88", "88",
                List.of(seat("A", 1, 0, 0, "0", "0", true))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    @Test
    @DisplayName("Rule 1: blank-after-trim row label is rejected")
    void rule1_blankRowLabelRejected() {
        var sections = List.of(section("Orchestra", null, 2, 2, "88", "88",
                List.of(seat("   ", 1, 0, 0, "0", "0", true))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    @Test
    @DisplayName("Rule 1: trimmed names pass validation")
    void rule1_trimmedNamesPass() {
        var sections = List.of(section("  Orchestra  ", null, 2, 2, "88", "88",
                List.of(seat("  A  ", 1, 0, 0, "0", "0", true))));
        validationService.validate(venue, request(sections, List.of()), emptyIds());
    }

    // ------------------------------------------------------------------
    // Rule 2: section names unique across active sections
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Rule 2: duplicate active section names rejected")
    void rule2_duplicateActiveSectionNamesRejected() {
        var sections = List.of(
                section("Orchestra", null, 2, 5, "220", "88",
                        List.of(seat("A", 1, 0, 0, "0", "0", true))),
                section("Orchestra", null, 2, 5, "220", "88",
                        List.of(seat("A", 1, 0, 0, "0", "0", true))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    @ParameterizedTest(name = "Rule 2 case/whitespace duplicate: [{0}] vs [{1}]")
    @ValueSource(strings = {"orchestra|Orchestra", "ORCHESTRA|orchestra", "  Orchestra|Orchestra  "})
    @DisplayName("Rule 2: case/whitespace duplicates rejected")
    void rule2_caseWhitespaceDuplicatesRejected(String pair) {
        String[] parts = pair.split("\\|");
        var sections = List.of(
                section(parts[0], null, 2, 5, "220", "88",
                        List.of(seat("A", 1, 0, 0, "0", "0", true))),
                section(parts[1], null, 2, 5, "220", "88",
                        List.of(seat("A", 1, 0, 0, "0", "0", true))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    @Test
    @DisplayName("Rule 2: duplicate names allowed when one section is inactive")
    void rule2_inactiveDuplicateAllowed() {
        var active = sectionFull("Orchestra", null, true, "0", "0", "220", "88", "0", 0,
                null, 2, 5, List.of(seat("A", 1, 0, 0, "0", "0", true)));
        var inactive = sectionFull("orchestra", null, false, "0", "100", "220", "88", "0", 0,
                null, 2, 5, List.of(seat("A", 1, 0, 0, "0", "0", false)));
        validationService.validate(venue, request(List.of(active, inactive), List.of()), emptyIds());
    }

    // ------------------------------------------------------------------
    // Rule 3: section IDs unique + owned
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Rule 3: duplicate section IDs rejected")
    void rule3_duplicateSectionIdsRejected() {
        UUID sectionId = UUID.randomUUID();
        var ids = new LayoutValidationService.ExistingLayoutIds(Set.of(sectionId), Map.of(), Set.of());
        var sections = List.of(
                sectionFull("Alpha", sectionId, true, "0", "0", "220", "88", "0", 0, null, 2, 5,
                        List.of(seat("A", 1, 0, 0, "0", "0", true))),
                sectionFull("Beta", sectionId, true, "0", "100", "220", "88", "0", 0, null, 2, 5,
                        List.of(seat("A", 1, 0, 0, "0", "0", true))));
        assertInvalid(request(sections, List.of()), ids);
    }

    @Test
    @DisplayName("Rule 3: foreign section ID rejected")
    void rule3_foreignSectionIdRejected() {
        UUID owned = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        var ids = new LayoutValidationService.ExistingLayoutIds(Set.of(owned), Map.of(), Set.of());
        var sections = List.of(
                sectionFull("Alpha", foreign, true, "0", "0", "220", "88", "0", 0, null, 2, 5,
                        List.of(seat("A", 1, 0, 0, "0", "0", true))));
        assertInvalid(request(sections, List.of()), ids);
    }

    // ------------------------------------------------------------------
    // Rule 4: seat IDs unique + owned + no moves
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Rule 4: duplicate seat IDs across sections rejected")
    void rule4_duplicateSeatIdsRejected() {
        UUID sectionA = UUID.randomUUID();
        UUID sectionB = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        var ids = new LayoutValidationService.ExistingLayoutIds(
                Set.of(sectionA, sectionB),
                Map.of(seatId, sectionA), Set.of());
        var sections = List.of(
                sectionFull("Alpha", sectionA, true, "0", "0", "220", "88", "0", 0, null, 2, 5,
                        List.of(seatWithId(seatId, "A", 1, 0, 0, "0", "0", true))),
                sectionFull("Beta", sectionB, true, "0", "100", "220", "88", "0", 0, null, 2, 5,
                        List.of(seatWithId(seatId, "A", 1, 0, 0, "0", "0", true))));
        assertInvalid(request(sections, List.of()), ids);
    }

    @Test
    @DisplayName("Rule 4: foreign seat ID rejected")
    void rule4_foreignSeatIdRejected() {
        UUID sectionId = UUID.randomUUID();
        var ids = new LayoutValidationService.ExistingLayoutIds(Set.of(sectionId), Map.of(), Set.of());
        var sections = List.of(
                sectionFull("Alpha", sectionId, true, "0", "0", "220", "88", "0", 0, null, 2, 5,
                        List.of(seatWithId(UUID.randomUUID(), "A", 1, 0, 0, "0", "0", true))));
        assertInvalid(request(sections, List.of()), ids);
    }

    @Test
    @DisplayName("Rule 4: moved seat between sections rejected")
    void rule4_movedSeatRejected() {
        UUID sectionA = UUID.randomUUID();
        UUID sectionB = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        var ids = new LayoutValidationService.ExistingLayoutIds(
                Set.of(sectionA, sectionB), Map.of(seatId, sectionA), Set.of());
        var sections = List.of(
                sectionFull("Alpha", sectionA, true, "0", "0", "220", "88", "0", 0, null, 2, 5, List.of()),
                sectionFull("Beta", sectionB, true, "0", "100", "220", "88", "0", 0, null, 2, 5,
                        List.of(seatWithId(seatId, "A", 1, 0, 0, "0", "0", true))));
        assertInvalid(request(sections, List.of()), ids);
    }

    // ------------------------------------------------------------------
    // Rule 5: row/number + grid uniqueness
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Rule 5: duplicate rowLabel/seatNumber rejected")
    void rule5_duplicateRowNumberRejected() {
        var sections = List.of(section("Orchestra", null, 2, 5, "220", "88",
                List.of(seat("A", 1, 0, 0, "0", "0", true),
                        seat("A", 1, 1, 0, "44", "0", true))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    @Test
    @DisplayName("Rule 5: duplicate grid rejected")
    void rule5_duplicateGridRejected() {
        var sections = List.of(section("Orchestra", null, 2, 5, "220", "88",
                List.of(seat("A", 1, 0, 0, "0", "0", true),
                        seat("B", 2, 0, 0, "44", "44", true))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    @Test
    @DisplayName("Rule 5: case/whitespace row duplicates rejected")
    void rule5_caseWhitespaceRowDuplicatesRejected() {
        var sections = List.of(section("Orchestra", null, 2, 5, "220", "88",
                List.of(seat("a", 1, 0, 0, "0", "0", true),
                        seat("  A ", 1, 1, 0, "44", "0", true))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    @Test
    @DisplayName("Rule 5: same row/seat in different sections accepted")
    void rule5_sameRowDifferentSectionsAccepted() {
        var sections = List.of(
                section("Alpha", null, 2, 5, "220", "88",
                        List.of(seat("A", 1, 0, 0, "0", "0", true))),
                section("Beta", null, 2, 5, "220", "88",
                        List.of(seat("A", 1, 0, 0, "0", "0", true))));
        validationService.validate(venue, request(sections, List.of()), emptyIds());
    }

    @Test
    @DisplayName("Rule 5: inactive seats still reject duplicate row/grid")
    void rule5_inactiveDuplicatesStillRejected() {
        var sections = List.of(section("Orchestra", null, 2, 5, "220", "88",
                List.of(seat("A", 1, 0, 0, "0", "0", false),
                        seat("A", 1, 1, 0, "44", "0", false))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    // ------------------------------------------------------------------
    // Rule 6: active position uniqueness with normalization
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Rule 6: duplicate active positions rejected")
    void rule6_duplicateActivePositionsRejected() {
        var sections = List.of(section("Orchestra", null, 2, 5, "220", "88",
                List.of(seat("A", 1, 0, 0, "10", "10", true),
                        seat("A", 2, 1, 0, "10", "10", true))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    @Test
    @DisplayName("Rule 6: scale variants 1.0 and 1.000 count as same position")
    void rule6_scaleVariantsSamePosition() {
        var sections = List.of(section("Orchestra", null, 2, 5, "220", "88",
                List.of(seat("A", 1, 0, 0, "1.0", "2.0", true),
                        seat("A", 2, 1, 0, "1.000", "2.000", true))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    @Test
    @DisplayName("Rule 6: inactive seats may share positions")
    void rule6_inactiveSharingAllowed() {
        var sections = List.of(section("Orchestra", null, 2, 5, "220", "88",
                List.of(seat("A", 1, 0, 0, "10", "10", false),
                        seat("A", 2, 1, 0, "10", "10", false))));
        validationService.validate(venue, request(sections, List.of()), emptyIds());
    }

    @Test
    @DisplayName("Rule 6: active vs inactive sharing allowed")
    void rule6_activeInactiveSharingAllowed() {
        var sections = List.of(section("Orchestra", null, 2, 5, "220", "88",
                List.of(seat("A", 1, 0, 0, "10", "10", true),
                        seat("A", 2, 1, 0, "10", "10", false))));
        validationService.validate(venue, request(sections, List.of()), emptyIds());
    }

    // ------------------------------------------------------------------
    // Rule 7: section bounds
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Rule 7: inclusive section boundaries accepted")
    void rule7_inclusiveBoundariesAccepted() {
        var sections = List.of(
                sectionFull("Min", null, true, "0", "0", "0.001", "0.001", "-180", -1000,
                        null, 1, 1, List.of(seat("A", 1, 0, 0, "0", "0", true))),
                sectionFull("Max", null, true, "100000", "100000", "100000", "100000", "180", 1000,
                        null, 1, 1, List.of(seat("A", 1, 0, 0, "100000", "100000", true))));
        // Second section width/height are 100000 so seat at 100000,100000 is in bounds.
        validationService.validate(venue, request(sections, List.of()), emptyIds());
    }

    @ParameterizedTest(name = "Rule 7 just-outside rejected: posX={0}")
    @ValueSource(strings = {"-0.001", "100000.001"})
    @DisplayName("Rule 7: section position just-outside rejected")
    void rule7_sectionPositionJustOutsideRejected(String posX) {
        var sections = List.of(
                sectionFull("S", null, true, posX, "0", "100", "100", "0", 0,
                        null, 2, 2, List.of(seat("A", 1, 0, 0, "0", "0", true))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    @ParameterizedTest(name = "Rule 7 width just-outside: {0}")
    @ValueSource(strings = {"0", "0.000", "-1", "100000.001"})
    @DisplayName("Rule 7: section width just-outside rejected")
    void rule7_sectionWidthJustOutsideRejected(String width) {
        var sections = List.of(
                sectionFull("S", null, true, "0", "0", width, "100", "0", 0,
                        null, 2, 2, List.of(seat("A", 1, 0, 0, "0", "0", true))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    @ParameterizedTest(name = "Rule 7 rotation just-outside: {0}")
    @ValueSource(strings = {"-180.001", "180.001"})
    @DisplayName("Rule 7: section rotation just-outside rejected")
    void rule7_sectionRotationJustOutsideRejected(String rotation) {
        var sections = List.of(
                sectionFull("S", null, true, "0", "0", "100", "100", rotation, 0,
                        null, 2, 2, List.of(seat("A", 1, 0, 0, "0", "0", true))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    @ParameterizedTest(name = "Rule 7 zIndex just-outside: {0}")
    @ValueSource(ints = {-1001, 1001})
    @DisplayName("Rule 7: section zIndex just-outside rejected")
    void rule7_sectionZJustOutsideRejected(int z) {
        var sections = List.of(
                sectionFull("S", null, true, "0", "0", "100", "100", "0", z,
                        null, 2, 2, List.of(seat("A", 1, 0, 0, "0", "0", true))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    // ------------------------------------------------------------------
    // Rule 8: seat local + grid bounds
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Rule 8: seat at section edges accepted")
    void rule8_seatEdgesAccepted() {
        var sections = List.of(section("Orchestra", null, 2, 2, "100", "100",
                List.of(seat("A", 1, 0, 0, "0", "0", true),
                        seat("A", 2, 1, 1, "100", "100", true))));
        validationService.validate(venue, request(sections, List.of()), emptyIds());
    }

    @Test
    @DisplayName("Rule 8: seat position outside section rejected")
    void rule8_seatPositionOutsideRejected() {
        var sections = List.of(section("Orchestra", null, 2, 2, "100", "100",
                List.of(seat("A", 1, 0, 0, "100.001", "0", true))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    @Test
    @DisplayName("Rule 8: gridX equal to colCount rejected")
    void rule8_gridXEqualColCountRejected() {
        // colCount=2 allows gridX 0,1 only.
        var sections = List.of(section("Orchestra", null, 2, 2, "100", "100",
                List.of(seat("A", 1, 2, 0, "10", "10", true))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    @Test
    @DisplayName("Rule 8: gridY equal to rowCount rejected")
    void rule8_gridYEqualRowCountRejected() {
        var sections = List.of(section("Orchestra", null, 2, 2, "100", "100",
                List.of(seat("A", 1, 0, 2, "10", "10", true))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    // ------------------------------------------------------------------
    // Rule 9: shapeMetadata object
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Rule 9: null shapeMetadata accepted")
    void rule9_nullMetadataAccepted() {
        var sections = List.of(sectionFull("S", null, true, "0", "0", "100", "100", "0", 0,
                null, 1, 1, List.of(seat("A", 1, 0, 0, "0", "0", true))));
        validationService.validate(venue, request(sections, List.of()), emptyIds());
    }

    @Test
    @DisplayName("Rule 9: object shapeMetadata accepted")
    void rule9_objectMetadataAccepted() {
        JsonNode obj = objectMapper.createObjectNode().put("radius", 5);
        var sections = List.of(sectionFull("S", null, true, "0", "0", "100", "100", "0", 0,
                obj, 1, 1, List.of(seat("A", 1, 0, 0, "0", "0", true))));
        validationService.validate(venue, request(sections, List.of()), emptyIds());
    }

    @Test
    @DisplayName("Rule 9: non-object shapeMetadata rejected")
    void rule9_nonObjectMetadataRejected() {
        JsonNode arr = objectMapper.createArrayNode().add("x");
        var sections = List.of(sectionFull("S", null, true, "0", "0", "100", "100", "0", 0,
                arr, 1, 1, List.of(seat("A", 1, 0, 0, "0", "0", true))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    // ------------------------------------------------------------------
    // Rule 10: capacity
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Rule 10: exact capacity accepted")
    void rule10_exactCapacityAccepted() {
        Venue small = Venue.builder().id(UUID.randomUUID()).name("Small").capacity(2).layoutVersion(0L).build();
        var sections = List.of(section("Orchestra", null, 1, 2, "88", "44",
                List.of(seat("A", 1, 0, 0, "0", "0", true),
                        seat("A", 2, 1, 0, "44", "0", true))));
        validationService.validate(small, request(sections, List.of()), emptyIds());
    }

    @Test
    @DisplayName("Rule 10: capacity plus one rejected")
    void rule10_capacityPlusOneRejected() {
        Venue small = Venue.builder().id(UUID.randomUUID()).name("Small").capacity(2).layoutVersion(0L).build();
        var sections = List.of(section("Orchestra", null, 1, 3, "132", "44",
                List.of(seat("A", 1, 0, 0, "0", "0", true),
                        seat("A", 2, 1, 0, "44", "0", true),
                        seat("A", 3, 2, 0, "88", "0", true))));
        assertThatThrownBy(() -> validationService.validate(small, request(sections, List.of()), emptyIds()))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("Rule 10: active seat inside inactive section rejected")
    void rule10_activeSeatInInactiveSectionRejected() {
        var sections = List.of(sectionFull("S", null, false, "0", "0", "100", "100", "0", 0,
                null, 1, 1, List.of(seat("A", 1, 0, 0, "0", "0", true))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    @Test
    @DisplayName("Rule 10: inactive seats do not count toward capacity")
    void rule10_inactiveSeatsExcluded() {
        Venue small = Venue.builder().id(UUID.randomUUID()).name("Small").capacity(1).layoutVersion(0L).build();
        var sections = List.of(section("Orchestra", null, 1, 2, "88", "44",
                List.of(seat("A", 1, 0, 0, "0", "0", true),
                        seat("A", 2, 1, 0, "44", "0", false))));
        validationService.validate(small, request(sections, List.of()), emptyIds());
    }

    // ------------------------------------------------------------------
    // Rule 11: element geometry bounds
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Rule 11: inclusive element boundaries accepted")
    void rule11_inclusiveBoundariesAccepted() {
        var elements = List.of(
                element(null, LayoutElementType.STAGE, "Stage",
                        geometry("0", "0", "0.001", "0.001", "-180"), -1000),
                element(null, LayoutElementType.AISLE, null,
                        geometry("100000", "100000", "100000", "100000", "180"), 1000));
        validationService.validate(venue, request(List.of(), elements), emptyIds());
    }

    @ParameterizedTest(name = "Rule 11 geometry just-outside: x={0}")
    @ValueSource(strings = {"-0.001", "100000.001"})
    @DisplayName("Rule 11: element x just-outside rejected")
    void rule11_elementXJustOutsideRejected(String x) {
        var elements = List.of(
                element(null, LayoutElementType.STAGE, "Stage", geometry(x, "0", "10", "10", "0"), 0));
        assertInvalid(request(List.of(), elements), emptyIds());
    }

    @ParameterizedTest(name = "Rule 11 geometry width just-outside: {0}")
    @ValueSource(strings = {"0", "100000.001"})
    @DisplayName("Rule 11: element width just-outside rejected")
    void rule11_elementWidthJustOutsideRejected(String w) {
        var elements = List.of(
                element(null, LayoutElementType.STAGE, "Stage", geometry("0", "0", w, "10", "0"), 0));
        assertInvalid(request(List.of(), elements), emptyIds());
    }

    @ParameterizedTest(name = "Rule 11 rotation just-outside: {0}")
    @ValueSource(strings = {"-180.001", "180.001"})
    @DisplayName("Rule 11: element rotation just-outside rejected")
    void rule11_elementRotationJustOutsideRejected(String rot) {
        var elements = List.of(
                element(null, LayoutElementType.STAGE, "Stage", geometry("0", "0", "10", "10", rot), 0));
        assertInvalid(request(List.of(), elements), emptyIds());
    }

    // ------------------------------------------------------------------
    // Rule 12: element labels
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Rule 12: LABEL without label rejected")
    void rule12_labelRequiresLabel() {
        var elements = List.of(
                element(null, LayoutElementType.LABEL, null, geometry("0", "0", "10", "10", "0"), 0));
        assertInvalid(request(List.of(), elements), emptyIds());
    }

    @Test
    @DisplayName("Rule 12: LABEL with blank label rejected")
    void rule12_labelBlankRejected() {
        var elements = List.of(
                element(null, LayoutElementType.LABEL, "   ", geometry("0", "0", "10", "10", "0"), 0));
        assertInvalid(request(List.of(), elements), emptyIds());
    }

    @Test
    @DisplayName("Rule 12: non-LABEL permits null and blank labels")
    void rule12_nonLabelPermitsBlank() {
        var elements = List.of(
                element(null, LayoutElementType.STAGE, null, geometry("0", "0", "10", "10", "0"), 0),
                element(null, LayoutElementType.AISLE, "  ", geometry("20", "0", "10", "10", "0"), 1));
        validationService.validate(venue, request(List.of(), elements), emptyIds());
    }

    @Test
    @DisplayName("Rule 12: label over 255 characters rejected")
    void rule12_labelTooLongRejected() {
        String tooLong = "x".repeat(256);
        var elements = List.of(
                element(null, LayoutElementType.STAGE, tooLong, geometry("0", "0", "10", "10", "0"), 0));
        assertInvalid(request(List.of(), elements), emptyIds());
    }

    // ------------------------------------------------------------------
    // Rule 13: element IDs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Rule 13: duplicate element IDs rejected")
    void rule13_duplicateElementIdsRejected() {
        UUID elementId = UUID.randomUUID();
        var ids = new LayoutValidationService.ExistingLayoutIds(Set.of(), Map.of(), Set.of(elementId));
        var elements = List.of(
                element(elementId, LayoutElementType.STAGE, "A", geometry("0", "0", "10", "10", "0"), 0),
                element(elementId, LayoutElementType.AISLE, "B", geometry("20", "0", "10", "10", "0"), 1));
        assertInvalid(request(List.of(), elements), ids);
    }

    @Test
    @DisplayName("Rule 13: foreign element ID rejected")
    void rule13_foreignElementIdRejected() {
        var ids = new LayoutValidationService.ExistingLayoutIds(Set.of(), Map.of(), Set.of(UUID.randomUUID()));
        var elements = List.of(
                element(UUID.randomUUID(), LayoutElementType.STAGE, "A", geometry("0", "0", "10", "10", "0"), 0));
        assertInvalid(request(List.of(), elements), ids);
    }

    // ------------------------------------------------------------------
    // Rule 14: zero allowed, null entries rejected
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Rule 14: empty typed layout accepted")
    void rule14_emptyLayoutAccepted() {
        validationService.validate(venue, request(List.of(), List.of()), emptyIds());
    }

    @Test
    @DisplayName("Rule 14: null section entry rejected")
    void rule14_nullSectionRejected() {
        List<SaveVenueLayoutRequest.SectionUpsert> sections = new ArrayList<>();
        sections.add(null);
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    @Test
    @DisplayName("Rule 14: null seat entry rejected")
    void rule14_nullSeatRejected() {
        List<SaveVenueLayoutRequest.SeatUpsert> seats = new ArrayList<>();
        seats.add(null);
        var sections = List.of(section("Orchestra", null, 2, 2, "88", "88", seats));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    @Test
    @DisplayName("Rule 14: null element entry rejected")
    void rule14_nullElementRejected() {
        List<SaveVenueLayoutRequest.LayoutElementUpsert> elements = new ArrayList<>();
        elements.add(null);
        assertInvalid(request(List.of(), elements), emptyIds());
    }

    // ------------------------------------------------------------------
    // Parameterized duplicate matrix
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Duplicate section names with different whitespace still rejected")
    void duplicateNames_whitespaceVariants() {
        var sections = List.of(
                section(" Balcony", null, 1, 1, "44", "44",
                        List.of(seat("A", 1, 0, 0, "0", "0", true))),
                section("Balcony ", null, 1, 1, "44", "44",
                        List.of(seat("A", 1, 0, 0, "0", "0", true))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }

    // ------------------------------------------------------------------
    // Additive JSON serialization round-trip
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Additive JSON round-trip preserves legacy and typed fields")
    void additiveJsonRoundTrip() throws Exception {
        UUID venueId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID elementId = UUID.randomUUID();

        SeatResponse seat = new SeatResponse(seatId, "A", 1, 0, 0, true,
                new BigDecimal("0.000"), new BigDecimal("0.000"));
        SectionLayoutResponse section = new SectionLayoutResponse(
                sectionId, "Orchestra", 10, 20, List.of(seat),
                true, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("880.000"), new BigDecimal("440.000"),
                BigDecimal.ZERO, 0, null);
        LayoutElementResponse.Geometry elementGeometry =
                new LayoutElementResponse.Geometry(
                        BigDecimal.ZERO, BigDecimal.ZERO,
                        new BigDecimal("100.000"), new BigDecimal("50.000"), BigDecimal.ZERO);
        LayoutElementResponse layoutElement = new LayoutElementResponse(
                elementId, LayoutElementType.STAGE, "Main Stage", elementGeometry, 1);
        VenueSeatMapLayoutResponse response = new VenueSeatMapLayoutResponse(
                venueId, "Grand Theatre", 500, 1L, List.of(section), 7L, List.of(layoutElement));

        String json = objectMapper.writeValueAsString(response);
        JsonNode tree = objectMapper.readTree(json);

        // Legacy fields unchanged in name and scalar type.
        assertThat(tree.get("venueId").asText()).isEqualTo(venueId.toString());
        assertThat(tree.get("name").asText()).isEqualTo("Grand Theatre");
        assertThat(tree.get("capacity").asInt()).isEqualTo(500);
        assertThat(tree.get("totalConfiguredSeats").asLong()).isEqualTo(1L);
        assertThat(tree.get("sections").get(0).get("sectionId").asText()).isEqualTo(sectionId.toString());
        assertThat(tree.get("sections").get(0).get("name").asText()).isEqualTo("Orchestra");
        assertThat(tree.get("sections").get(0).get("rowCount").asInt()).isEqualTo(10);
        assertThat(tree.get("sections").get(0).get("colCount").asInt()).isEqualTo(20);
        assertThat(tree.get("sections").get(0).get("seats").get(0).get("seatId").asText())
                .isEqualTo(seatId.toString());
        assertThat(tree.get("sections").get(0).get("seats").get(0).get("rowLabel").asText()).isEqualTo("A");
        assertThat(tree.get("sections").get(0).get("seats").get(0).get("seatNumber").asInt()).isEqualTo(1);
        assertThat(tree.get("sections").get(0).get("seats").get(0).get("gridX").asInt()).isZero();
        assertThat(tree.get("sections").get(0).get("seats").get(0).get("gridY").asInt()).isZero();
        assertThat(tree.get("sections").get(0).get("seats").get(0).get("isActive").asBoolean()).isTrue();

        // New typed fields round-trip (numeric comparison ignores JSON tree scale normalization).
        assertThat(tree.get("layoutVersion").asLong()).isEqualTo(7L);
        assertThat(tree.get("sections").get(0).get("isActive").asBoolean()).isTrue();
        assertThat(tree.get("sections").get(0).get("positionX").decimalValue().compareTo(BigDecimal.ZERO))
                .isZero();
        assertThat(tree.get("sections").get(0).get("seats").get(0).get("positionX").decimalValue()
                .compareTo(new BigDecimal("0.000"))).isZero();
        assertThat(tree.get("elements").get(0).get("elementId").asText()).isEqualTo(elementId.toString());
        assertThat(tree.get("elements").get(0).get("type").asText()).isEqualTo("STAGE");
        assertThat(tree.get("elements").get(0).get("label").asText()).isEqualTo("Main Stage");
        assertThat(tree.get("elements").get(0).get("geometry").get("x").decimalValue()
                .compareTo(BigDecimal.ZERO)).isZero();
        assertThat(tree.get("elements").get(0).get("geometry").get("width").decimalValue()
                .compareTo(new BigDecimal("100.000"))).isZero();

        // Deserialize back and compare: legacy fields strictly equal, typed numerics by value.
        VenueSeatMapLayoutResponse parsed =
                objectMapper.readValue(json, VenueSeatMapLayoutResponse.class);
        assertThat(parsed.venueId()).isEqualTo(response.venueId());
        assertThat(parsed.name()).isEqualTo(response.name());
        assertThat(parsed.capacity()).isEqualTo(response.capacity());
        assertThat(parsed.totalConfiguredSeats()).isEqualTo(response.totalConfiguredSeats());
        assertThat(parsed.layoutVersion()).isEqualTo(response.layoutVersion());
        assertThat(parsed.sections()).hasSize(1);
        assertThat(parsed.sections().getFirst().sectionId()).isEqualTo(sectionId);
        assertThat(parsed.sections().getFirst().name()).isEqualTo("Orchestra");
        assertThat(parsed.sections().getFirst().rowCount()).isEqualTo(10);
        assertThat(parsed.sections().getFirst().colCount()).isEqualTo(20);
        assertThat(parsed.sections().getFirst().isActive()).isTrue();
        assertThat(parsed.sections().getFirst().positionX().compareTo(BigDecimal.ZERO)).isZero();
        assertThat(parsed.sections().getFirst().width().compareTo(new BigDecimal("880.000"))).isZero();
        assertThat(parsed.sections().getFirst().seats()).hasSize(1);
        assertThat(parsed.sections().getFirst().seats().getFirst().seatId()).isEqualTo(seatId);
        assertThat(parsed.sections().getFirst().seats().getFirst().rowLabel()).isEqualTo("A");
        assertThat(parsed.sections().getFirst().seats().getFirst().seatNumber()).isEqualTo(1);
        assertThat(parsed.sections().getFirst().seats().getFirst().gridX()).isZero();
        assertThat(parsed.sections().getFirst().seats().getFirst().gridY()).isZero();
        assertThat(parsed.sections().getFirst().seats().getFirst().isActive()).isTrue();
        assertThat(parsed.sections().getFirst().seats().getFirst().positionX()
                .compareTo(new BigDecimal("0.000"))).isZero();
        assertThat(parsed.elements()).hasSize(1);
        assertThat(parsed.elements().getFirst().elementId()).isEqualTo(elementId);
        assertThat(parsed.elements().getFirst().type()).isEqualTo(LayoutElementType.STAGE);
        assertThat(parsed.elements().getFirst().label()).isEqualTo("Main Stage");
        assertThat(parsed.elements().getFirst().geometry().x().compareTo(BigDecimal.ZERO)).isZero();
        assertThat(parsed.elements().getFirst().geometry().width()
                .compareTo(new BigDecimal("100.000"))).isZero();
    }

    @Test
    @DisplayName("Opaque geometry rejected: section metadata must be object")
    void opaqueGeometry_rejected() {
        JsonNode text = objectMapper.getNodeFactory().textNode("opaque");
        var sections = List.of(sectionFull("S", null, true, "0", "0", "100", "100", "0", 0,
                text, 1, 1, List.of(seat("A", 1, 0, 0, "0", "0", true))));
        assertInvalid(request(sections, List.of()), emptyIds());
    }
}
