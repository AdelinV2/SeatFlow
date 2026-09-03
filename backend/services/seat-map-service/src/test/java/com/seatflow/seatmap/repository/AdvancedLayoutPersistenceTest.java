package com.seatflow.seatmap.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.seatmap.model.entity.Seat;
import com.seatflow.seatmap.model.entity.Venue;
import com.seatflow.seatmap.model.entity.VenueLayoutElement;
import com.seatflow.seatmap.model.entity.VenueSection;
import com.seatflow.seatmap.model.enums.LayoutElementType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TASK-P11-002 verification: advanced layout persistence maps V5 without changing identity.
 * Covers section transforms, seat continuous positions, element geometry round-trip,
 * active/editor filtering, UUID stability, ordering and V5 constraint parity.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class AdvancedLayoutPersistenceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_seatmap_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private VenueSectionRepository sectionRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private VenueLayoutElementRepository elementRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanUp() {
        elementRepository.deleteAll();
        seatRepository.deleteAll();
        sectionRepository.deleteAll();
        venueRepository.deleteAll();
    }

    private Venue saveVenue(String name, String city) {
        return venueRepository.saveAndFlush(Venue.builder()
                .name(name).address("1 Test Ave").city(city).country("USA").capacity(1000)
                .build());
    }

    private VenueSection saveSection(Venue venue, String name, int zIndex, boolean active) {
        return sectionRepository.saveAndFlush(VenueSection.builder()
                .venue(venue).name(name).rowCount(2).colCount(2)
                .isActive(active)
                .positionX(bd("10.500")).positionY(bd("20.250"))
                .width(bd("88.000")).height(bd("88.000"))
                .rotationDeg(bd("15.000")).zIndex(zIndex)
                .build());
    }

    private Seat saveSeat(VenueSection section, String rowLabel, int seatNumber,
                          int gridX, int gridY, String posX, String posY, boolean active) {
        return seatRepository.saveAndFlush(Seat.builder()
                .section(section).rowLabel(rowLabel).seatNumber(seatNumber)
                .gridX(gridX).gridY(gridY).isActive(active)
                .positionX(bd(posX)).positionY(bd(posY))
                .build());
    }

    private VenueLayoutElement saveElement(Venue venue, LayoutElementType type, String label,
                                           JsonNode geometry, int zIndex) {
        return elementRepository.saveAndFlush(VenueLayoutElement.builder()
                .venue(venue).type(type).label(label).geometry(geometry).zIndex(zIndex)
                .build());
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value).setScale(3, RoundingMode.HALF_UP);
    }

    private JsonNode obj(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid test JSON: " + json, e);
        }
    }

    @Test
    @DisplayName("LayoutElementType exposes exactly the V5 enum values")
    void shouldExposeExactlyV5ElementTypes() {
        assertThat(LayoutElementType.values())
                .containsExactly(LayoutElementType.STAGE, LayoutElementType.AISLE,
                        LayoutElementType.LABEL, LayoutElementType.BARRIER,
                        LayoutElementType.DECORATION);
    }

    @Test
    @DisplayName("Section transforms and shape metadata round-trip; null metadata allowed")
    void shouldPersistAndReloadSectionTransforms() {
        Venue venue = saveVenue("Transform Hall", "NYC");

        VenueSection withMeta = sectionRepository.saveAndFlush(VenueSection.builder()
                .venue(venue).name("Main").rowCount(3).colCount(4)
                .positionX(bd("100.125")).positionY(bd("200.500"))
                .width(bd("176.000")).height(bd("132.000"))
                .rotationDeg(bd("-45.500")).zIndex(7)
                .shapeMetadata(obj("{\"kind\":\"rect\",\"radius\":12}"))
                .build());
        VenueSection nullMeta = saveSection(venue, "Plain", 1, true);

        VenueSection reloaded = sectionRepository.findById(withMeta.getId()).orElseThrow();
        assertThat(reloaded.getPositionX()).isEqualByComparingTo("100.125");
        assertThat(reloaded.getPositionY()).isEqualByComparingTo("200.500");
        assertThat(reloaded.getWidth()).isEqualByComparingTo("176.000");
        assertThat(reloaded.getHeight()).isEqualByComparingTo("132.000");
        assertThat(reloaded.getRotationDeg()).isEqualByComparingTo("-45.500");
        assertThat(reloaded.getZIndex()).isEqualTo(7);
        assertThat(reloaded.getIsActive()).isTrue();
        assertThat(reloaded.getShapeMetadata().get("kind").asText()).isEqualTo("rect");
        assertThat(reloaded.getShapeMetadata().get("radius").asInt()).isEqualTo(12);

        assertThat(sectionRepository.findById(nullMeta.getId()).orElseThrow().getShapeMetadata()).isNull();
    }

    @Test
    @DisplayName("Seat continuous positions persist while grid coordinates are retained")
    void shouldPersistSeatPositionsAndRetainGrid() {
        Venue venue = saveVenue("Seat Hall", "LA");
        VenueSection section = saveSection(venue, "Floor", 0, true);

        Seat seat = saveSeat(section, "A", 1, 2, 3, "88.000", "132.000", true);

        Seat reloaded = seatRepository.findById(seat.getId()).orElseThrow();
        assertThat(reloaded.getPositionX()).isEqualByComparingTo("88.000");
        assertThat(reloaded.getPositionY()).isEqualByComparingTo("132.000");
        assertThat(reloaded.getGridX()).isEqualTo(2);
        assertThat(reloaded.getGridY()).isEqualTo(3);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Layout element geometry round-trips as object JSON with timestamps")
    void shouldRoundTripElementGeometry() {
        Venue venue = saveVenue("Element Hall", "CHI");
        JsonNode geometry = obj("{\"x\":10,\"y\":20,\"w\":300,\"h\":40}");

        VenueLayoutElement saved = saveElement(venue, LayoutElementType.STAGE, "Main Stage", geometry, 5);

        VenueLayoutElement reloaded = elementRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getType()).isEqualTo(LayoutElementType.STAGE);
        assertThat(reloaded.getLabel()).isEqualTo("Main Stage");
        assertThat(reloaded.getGeometry()).isEqualTo(geometry);
        assertThat(reloaded.getGeometry().get("w").asInt()).isEqualTo(300);
        assertThat(reloaded.getZIndex()).isEqualTo(5);
        assertThat(reloaded.getVenue().getId()).isEqualTo(venue.getId());
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
        assertThat(reloaded.toString()).doesNotContain("\"w\":300");
    }

    @Test
    @DisplayName("Null element geometry fails at flush")
    void shouldRejectNullElementGeometry() {
        Venue venue = saveVenue("Null Geo Hall", "NYC");

        assertThatThrownBy(() -> elementRepository.saveAndFlush(VenueLayoutElement.builder()
                .venue(venue).type(LayoutElementType.AISLE).geometry(null).zIndex(0)
                .build())).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("Array element geometry is rejected by V5 geometry check")
    void shouldRejectArrayElementGeometry() {
        Venue venue = saveVenue("Json Array Hall", "NYC");

        assertThatThrownBy(() -> elementRepository.saveAndFlush(VenueLayoutElement.builder()
                .venue(venue).type(LayoutElementType.LABEL).geometry(obj("[1,2]")).zIndex(0)
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertCheckConstraintViolation(ex, "chk_venue_layout_elements_geometry"));
    }

    @Test
    @DisplayName("String element geometry is rejected by V5 geometry check")
    void shouldRejectStringElementGeometry() {
        Venue venue = saveVenue("Json String Hall", "NYC");

        assertThatThrownBy(() -> elementRepository.saveAndFlush(VenueLayoutElement.builder()
                .venue(venue).type(LayoutElementType.LABEL).geometry(obj("\"hello\"")).zIndex(0)
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertCheckConstraintViolation(ex, "chk_venue_layout_elements_geometry"));
    }

    @Test
    @DisplayName("Non-object shape metadata is rejected by V5 shape check")
    void shouldRejectNonObjectShapeMetadata() {
        Venue venue = saveVenue("Json Meta Hall", "NYC");
        VenueSection section = saveSection(venue, "Floor", 0, true);
        section.setShapeMetadata(obj("[\"not\",\"object\"]"));

        assertThatThrownBy(() -> sectionRepository.saveAndFlush(section))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertCheckConstraintViolation(ex, "chk_venue_sections_shape_metadata"));
    }

    /**
     * Asserts the failure is the intended PostgreSQL check violation (SQLState 23514)
     * for the expected V5 constraint. Each caller runs in its own {@code @DataJpaTest}
     * transaction, so this cannot pass because of an earlier aborted transaction.
     */
    private static void assertCheckConstraintViolation(Throwable ex, String expectedConstraint) {
        StringBuilder chain = new StringBuilder();
        String checkViolationState = null;
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                chain.append(current.getMessage()).append(" | ");
            }
            if (current instanceof SQLException sqlEx && "23514".equals(sqlEx.getSQLState())) {
                checkViolationState = "23514";
            }
        }
        assertThat(checkViolationState)
                .as("expected PostgreSQL check_violation SQLState 23514, chain: %s", chain)
                .isEqualTo("23514");
        assertThat(chain.toString())
                .as("expected V5 constraint %s in chain: %s", expectedConstraint, chain)
                .contains(expectedConstraint);
    }

    @Test
    @DisplayName("Active queries exclude deactivated rows while editor queries retain them")
    void shouldFilterActiveVsEditorReads() {
        Venue venue = saveVenue("Filter Hall", "NYC");
        VenueSection active = saveSection(venue, "Active", 1, true);
        VenueSection inactive = saveSection(venue, "Inactive", 0, false);

        Seat activeSeat = saveSeat(active, "A", 1, 0, 0, "0.000", "0.000", true);
        Seat retiredSeat = saveSeat(active, "A", 2, 1, 0, "44.000", "0.000", false);
        Seat seatInInactiveSection = saveSeat(inactive, "A", 1, 0, 0, "0.000", "0.000", true);

        assertThat(sectionRepository.findByVenueIdAndIsActiveTrueOrderByZIndexAscNameAsc(venue.getId()))
                .extracting(VenueSection::getId).containsExactly(active.getId());
        assertThat(sectionRepository.findByVenueIdOrderByZIndexAscNameAsc(venue.getId()))
                .extracting(VenueSection::getId)
                .containsExactly(inactive.getId(), active.getId());

        assertThat(seatRepository.findBySectionIdForEditor(active.getId()))
                .extracting(Seat::getId)
                .containsExactlyInAnyOrder(activeSeat.getId(), retiredSeat.getId());
        assertThat(seatRepository.findBySectionIdForEditor(inactive.getId()))
                .extracting(Seat::getId).containsExactly(seatInInactiveSection.getId());

        assertThat(seatRepository.findActiveSeatsForVenueLayout(venue.getId()))
                .extracting(Seat::getId).containsExactly(activeSeat.getId());
    }

    @Test
    @DisplayName("Geometry updates preserve section, seat and element UUIDs and ownership")
    void shouldPreserveUuidsOnGeometryUpdate() {
        Venue venue = saveVenue("Stable Hall", "LA");
        VenueSection section = saveSection(venue, "Floor", 2, true);
        Seat seat = saveSeat(section, "A", 1, 0, 0, "0.000", "0.000", true);
        VenueLayoutElement element =
                saveElement(venue, LayoutElementType.BARRIER, "Rail", obj("{\"x\":1}"), 1);

        UUID sectionId = section.getId();
        UUID seatId = seat.getId();
        UUID elementId = element.getId();
        UUID venueId = venue.getId();
        UUID sectionVenueId = section.getVenue().getId();
        UUID seatSectionId = seat.getSection().getId();

        section.setPositionX(bd("500.000"));
        section.setPositionY(bd("600.000"));
        section.setShapeMetadata(obj("{\"kind\":\"poly\"}"));
        sectionRepository.saveAndFlush(section);

        seat.setPositionX(bd("44.000"));
        seat.setPositionY(bd("44.000"));
        seatRepository.saveAndFlush(seat);

        element.setGeometry(obj("{\"x\":2,\"moved\":true}"));
        element.setZIndex(9);
        elementRepository.saveAndFlush(element);

        assertThat(sectionRepository.findById(sectionId).orElseThrow().getVenue().getId())
                .isEqualTo(sectionVenueId);
        Seat reloadedSeat = seatRepository.findById(seatId).orElseThrow();
        assertThat(reloadedSeat.getSection().getId()).isEqualTo(seatSectionId);
        assertThat(reloadedSeat.getPositionX()).isEqualByComparingTo("44.000");
        VenueLayoutElement reloadedElement = elementRepository.findById(elementId).orElseThrow();
        assertThat(reloadedElement.getVenue().getId()).isEqualTo(venueId);
        assertThat(reloadedElement.getGeometry().get("moved").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Duplicate active positions fail while inactive collisions remain persistable")
    void shouldEnforceActivePositionUniqueness() {
        Venue venue = saveVenue("Unique Hall", "CHI");
        VenueSection section = saveSection(venue, "Floor", 0, true);
        saveSeat(section, "A", 1, 0, 0, "10.000", "10.000", true);

        // Inactive collision first: PostgreSQL aborts the whole transaction on the
        // duplicate-key error below, so the failing insert must come last.
        Seat inactiveCollision =
                saveSeat(section, "A", 3, 2, 0, "10.000", "10.000", false);
        assertThat(inactiveCollision.getId()).isNotNull();

        assertThatThrownBy(() -> saveSeat(section, "A", 2, 1, 0, "10.000", "10.000", true))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("Active venue layout orders by section z-index then seat position then id")
    void shouldOrderActiveSeatsByZIndexAndPosition() {
        Venue venue = saveVenue("Order Hall", "NYC");
        VenueSection back = saveSection(venue, "Back", 10, true);
        VenueSection front = saveSection(venue, "Front", -5, true);

        Seat backSeat = saveSeat(back, "A", 1, 0, 0, "0.000", "0.000", true);
        Seat frontLow = saveSeat(front, "A", 1, 0, 0, "200.000", "200.000", true);
        Seat frontHigh = saveSeat(front, "A", 2, 1, 0, "100.000", "50.000", true);

        List<Seat> layout = seatRepository.findActiveSeatsForVenueLayout(venue.getId());
        assertThat(layout).extracting(Seat::getId)
                .containsExactly(frontHigh.getId(), frontLow.getId(), backSeat.getId());
    }

    @Test
    @DisplayName("Legacy grid queries keep grid ordering for the same seat IDs")
    void shouldRetainLegacyGridOrdering() {
        Venue venue = saveVenue("Legacy Hall", "LA");
        VenueSection section = saveSection(venue, "Floor", 0, true);

        Seat positionFirst = saveSeat(section, "A", 1, 0, 1, "0.000", "0.000", true);
        Seat gridFirst = saveSeat(section, "A", 2, 1, 0, "500.000", "500.000", true);

        assertThat(seatRepository.findBySectionIdOrderByGridYAscGridXAsc(section.getId()))
                .extracting(Seat::getId).containsExactly(gridFirst.getId(), positionFirst.getId());
        assertThat(seatRepository.findActiveSeatsBySectionId(section.getId()))
                .extracting(Seat::getId).containsExactly(gridFirst.getId(), positionFirst.getId());
        List<Seat> editor = seatRepository.findBySectionIdForEditor(section.getId());
        assertThat(editor).extracting(Seat::getId)
                .containsExactly(positionFirst.getId(), gridFirst.getId());
        assertThat(editor).extracting(Seat::getId)
                .containsExactlyInAnyOrder(gridFirst.getId(), positionFirst.getId());
    }

    @Test
    @DisplayName("Venue layout version defaults to zero and is distinct from JPA version")
    void shouldManageLayoutVersionSeparatelyFromJpaVersion() {
        Venue venue = saveVenue("Version Hall", "NYC");
        assertThat(venue.getLayoutVersion()).isZero();
        assertThat(venue.getVersion()).isNotNull();

        venue.setLayoutVersion(3L);
        Venue saved = venueRepository.saveAndFlush(venue);
        assertThat(saved.getLayoutVersion()).isEqualTo(3L);

        Optional<Venue> locked = venueRepository.findByIdForLayoutUpdate(saved.getId());
        assertThat(locked).isPresent();
        assertThat(locked.orElseThrow().getLayoutVersion()).isEqualTo(3L);
        assertThat(venueRepository.findByIdForLayoutUpdate(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("Element venue queries order by z-index and deletes stay venue-scoped")
    void shouldScopeElementReadsAndDeletesByVenue() {
        Venue venueA = saveVenue("Venue A", "NYC");
        Venue venueB = saveVenue("Venue B", "LA");

        VenueLayoutElement low =
                saveElement(venueA, LayoutElementType.STAGE, "low", obj("{\"n\":1}"), -5);
        VenueLayoutElement high =
                saveElement(venueA, LayoutElementType.AISLE, "high", obj("{\"n\":2}"), 10);
        VenueLayoutElement other =
                saveElement(venueB, LayoutElementType.LABEL, "other", obj("{\"n\":3}"), -100);

        assertThat(elementRepository.findByVenueIdOrderByZIndexAscIdAsc(venueA.getId()))
                .extracting(VenueLayoutElement::getId)
                .containsExactly(low.getId(), high.getId());

        elementRepository.deleteByVenueIdAndIdIn(venueA.getId(), List.of(low.getId(), other.getId()));

        assertThat(elementRepository.findByVenueIdOrderByZIndexAscIdAsc(venueA.getId()))
                .extracting(VenueLayoutElement::getId).containsExactly(high.getId());
        assertThat(elementRepository.findByVenueIdOrderByZIndexAscIdAsc(venueB.getId()))
                .extracting(VenueLayoutElement::getId).containsExactly(other.getId());
    }

    @Test
    @DisplayName("Element referencing a missing venue fails on flush")
    void shouldRejectElementForMissingVenue() {
        Venue venue = saveVenue("FK Hall", "NYC");
        UUID missingVenueId = UUID.randomUUID();
        assertThat(missingVenueId).isNotEqualTo(venue.getId());

        assertThatThrownBy(() -> elementRepository.saveAndFlush(VenueLayoutElement.builder()
                .venue(Venue.builder().id(missingVenueId).name("ghost").address("x")
                        .city("NYC").country("USA").capacity(10).build())
                .type(LayoutElementType.DECORATION).geometry(obj("{\"x\":1}")).zIndex(0)
                .build())).isInstanceOf(RuntimeException.class);
    }
}
