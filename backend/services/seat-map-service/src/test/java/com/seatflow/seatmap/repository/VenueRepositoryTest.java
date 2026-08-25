package com.seatflow.seatmap.repository;

import com.seatflow.seatmap.model.entity.Venue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class VenueRepositoryTest {

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

    @Test
    void shouldSaveAndFindVenueById() {
        Venue venue = Venue.builder()
                .name("Test Venue").address("123 Main St")
                .city("New York").country("USA").capacity(500)
                .build();

        Venue saved = venueRepository.saveAndFlush(venue);

        assertThat(venueRepository.findById(saved.getId())).isPresent();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getVersion()).isNotNull();
    }

    @Test
    void shouldCheckExistsByNameAndCity() {
        venueRepository.save(Venue.builder()
                .name("Grand Theatre").address("456 Broadway")
                .city("Boston").country("USA").capacity(300)
                .build());

        assertThat(venueRepository.existsByNameAndCity("Grand Theatre", "Boston")).isTrue();
        assertThat(venueRepository.existsByNameAndCity("Grand Theatre", "Chicago")).isFalse();
    }

    @Test
    void shouldFilterByCity() {
        venueRepository.save(Venue.builder().name("V1").address("A").city("NYC").country("USA").capacity(100).build());
        venueRepository.save(Venue.builder().name("V2").address("B").city("LA").country("USA").capacity(200).build());

        Page<Venue> result = venueRepository.findByFilters("NYC", null, PageRequest.of(0, 20));
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getCity()).isEqualTo("NYC");
    }

    @Test
    void shouldSearchByName() {
        venueRepository.save(Venue.builder().name("Grand Opera House").address("A").city("NYC").country("USA").capacity(100).build());
        venueRepository.save(Venue.builder().name("City Arena").address("B").city("NYC").country("USA").capacity(200).build());

        Page<Venue> result = venueRepository.findByFilters(null, "Grand", PageRequest.of(0, 20));
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getName()).contains("Grand");
    }
}
