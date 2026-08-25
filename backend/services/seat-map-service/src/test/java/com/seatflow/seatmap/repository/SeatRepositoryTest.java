package com.seatflow.seatmap.repository;

import com.seatflow.seatmap.model.entity.Seat;
import com.seatflow.seatmap.model.entity.Venue;
import com.seatflow.seatmap.model.entity.VenueSection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class SeatRepositoryTest {

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

    private VenueSection testSection;

    @BeforeEach
    void setUp() {
        seatRepository.deleteAll();
        sectionRepository.deleteAll();
        venueRepository.deleteAll();

        Venue venue = venueRepository.save(Venue.builder()
                .name("Test Venue").address("123 St").city("NYC").country("USA").capacity(100).build());
        testSection = sectionRepository.save(VenueSection.builder()
                .venue(venue).name("Section A").rowCount(2).colCount(3).build());
    }

    @Test
    void shouldFindActiveSeatsBySectionId() {
        // Create active and inactive seats
        seatRepository.save(Seat.builder().section(testSection).rowLabel("A").seatNumber(1).gridX(0).gridY(0).isActive(true).build());
        seatRepository.save(Seat.builder().section(testSection).rowLabel("A").seatNumber(2).gridX(1).gridY(0).isActive(true).build());
        seatRepository.save(Seat.builder().section(testSection).rowLabel("A").seatNumber(3).gridX(2).gridY(0).isActive(false).build());

        List<Seat> activeSeats = seatRepository.findActiveSeatsBySectionId(testSection.getId());
        assertThat(activeSeats).hasSize(2);
        assertThat(activeSeats).allMatch(Seat::getIsActive);
    }

    @Test
    void shouldCountActiveSeatsBySection() {
        seatRepository.save(Seat.builder().section(testSection).rowLabel("A").seatNumber(1).gridX(0).gridY(0).isActive(true).build());
        seatRepository.save(Seat.builder().section(testSection).rowLabel("A").seatNumber(2).gridX(1).gridY(0).isActive(false).build());

        long count = seatRepository.countBySectionIdAndIsActiveTrue(testSection.getId());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void shouldFindSeatByIdAndSectionId() {
        Seat seat = seatRepository.save(Seat.builder()
                .section(testSection).rowLabel("B").seatNumber(1).gridX(0).gridY(1).isActive(true).build());

        assertThat(seatRepository.findByIdAndSectionId(seat.getId(), testSection.getId())).isPresent();
        assertThat(seatRepository.findByIdAndSectionId(seat.getId(), java.util.UUID.randomUUID())).isEmpty();
    }

    @Test
    void shouldCountActiveSeatsByVenueId() {
        seatRepository.save(Seat.builder().section(testSection).rowLabel("A").seatNumber(1).gridX(0).gridY(0).isActive(true).build());
        seatRepository.save(Seat.builder().section(testSection).rowLabel("A").seatNumber(2).gridX(1).gridY(0).isActive(true).build());
        seatRepository.save(Seat.builder().section(testSection).rowLabel("A").seatNumber(3).gridX(2).gridY(0).isActive(false).build());

        long count = seatRepository.countActiveSeatsByVenueId(testSection.getVenue().getId());
        assertThat(count).isEqualTo(2);
    }
}
