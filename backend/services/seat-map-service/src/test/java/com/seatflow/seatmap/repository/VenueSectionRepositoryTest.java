package com.seatflow.seatmap.repository;

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
class VenueSectionRepositoryTest {

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

    private Venue testVenue;

    @BeforeEach
    void setUp() {
        sectionRepository.deleteAll();
        venueRepository.deleteAll();

        testVenue = venueRepository.save(Venue.builder()
                .name("Section Test Venue").address("100 Main St")
                .city("Chicago").country("USA").capacity(500)
                .build());
    }

    @Test
    void shouldFindByVenueIdOrderByNameAsc() {
        sectionRepository.save(VenueSection.builder().venue(testVenue).name("Orchestra").rowCount(10).colCount(20).build());
        sectionRepository.save(VenueSection.builder().venue(testVenue).name("Balcony").rowCount(5).colCount(15).build());

        List<VenueSection> sections = sectionRepository.findByVenueIdOrderByNameAsc(testVenue.getId());

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).getName()).isEqualTo("Balcony");
        assertThat(sections.get(1).getName()).isEqualTo("Orchestra");
    }

    @Test
    void shouldCheckExistsByVenueIdAndName() {
        sectionRepository.save(VenueSection.builder().venue(testVenue).name("VIP Lounge").rowCount(2).colCount(5).build());

        assertThat(sectionRepository.existsByVenueIdAndName(testVenue.getId(), "VIP Lounge")).isTrue();
        assertThat(sectionRepository.existsByVenueIdAndName(testVenue.getId(), "General")).isFalse();
    }
}
