package com.seatflow.event.repository;

import com.seatflow.event.model.entity.Event;
import com.seatflow.event.model.entity.EventPricingTier;
import com.seatflow.event.model.enums.EventCategory;
import com.seatflow.event.model.enums.EventStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class EventRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_event_test")
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
    private EventRepository eventRepository;

    @Autowired
    private EventPricingTierRepository pricingTierRepository;

    @Autowired
    private EntityManager entityManager;

    private Event publishedEvent(EventCategory category, String title, Instant eventDate) {
        return Event.builder()
                .venueId(UUID.randomUUID())
                .title(title)
                .description("A compelling description")
                .category(category)
                .eventDate(eventDate)
                .status(EventStatus.PUBLISHED)
                .build();
    }

    private Event draftEvent() {
        return Event.builder()
                .venueId(UUID.randomUUID())
                .title("Draft Show")
                .description("Not yet public")
                .category(EventCategory.OTHER)
                .eventDate(Instant.now().plusSeconds(86400))
                .status(EventStatus.DRAFT)
                .build();
    }

    @Test
    void shouldSaveAndFindEventById() {
        Event saved = eventRepository.saveAndFlush(publishedEvent(EventCategory.CONCERT, "Hamlet", Instant.now().plusSeconds(86400)));

        Optional<Event> found = eventRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getCreatedAt()).isNotNull();
        assertThat(found.get().getVersion()).isNotNull();
        assertThat(found.get().getStatus()).isEqualTo(EventStatus.PUBLISHED);
    }

    @Test
    void catalogQueriesMustNotReturnDraftEvents() {
        eventRepository.saveAndFlush(publishedEvent(EventCategory.CONCERT, "Public Concert", Instant.now().plusSeconds(86400)));
        eventRepository.saveAndFlush(draftEvent());

        Specification<Event> publishedOnly = (root, q, cb) -> cb.equal(root.get("status"), EventStatus.PUBLISHED);
        Page<Event> result = eventRepository.findAll(publishedOnly, PageRequest.of(0, 20));

        assertThat(result.getContent()).isNotEmpty();
        assertThat(result.getContent()).allMatch(e -> e.getStatus() == EventStatus.PUBLISHED);
    }

    @Test
    void shouldFilterCatalogByCategory() {
        eventRepository.saveAndFlush(publishedEvent(EventCategory.CONCERT, "Symphony", Instant.now().plusSeconds(86400)));
        eventRepository.saveAndFlush(publishedEvent(EventCategory.SPORTS, "Derby", Instant.now().plusSeconds(86400)));

        Specification<Event> byCategory = (root, q, cb) -> cb.equal(root.get("category"), EventCategory.SPORTS);
        Page<Event> result = eventRepository.findAll(byCategory, PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getCategory()).isEqualTo(EventCategory.SPORTS);
    }

    @Test
    void shouldSearchCatalogByTitle() {
        eventRepository.saveAndFlush(publishedEvent(EventCategory.THEATRE, "Hamlet Reborn", Instant.now().plusSeconds(86400)));
        eventRepository.saveAndFlush(publishedEvent(EventCategory.THEATRE, "Macbeth", Instant.now().plusSeconds(86400)));

        Specification<Event> search = (root, q, cb) ->
                cb.like(cb.lower(root.get("title")), "%hamlet%");
        Page<Event> result = eventRepository.findAll(search, PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getTitle()).contains("Hamlet");
    }

    @Test
    void shouldExcludePastEventsFromUpcomingCatalog() {
        eventRepository.saveAndFlush(publishedEvent(EventCategory.CONCERT, "Past Gig", Instant.now().minusSeconds(86400)));
        eventRepository.saveAndFlush(publishedEvent(EventCategory.CONCERT, "Future Gig", Instant.now().plusSeconds(86400)));

        Specification<Event> upcomingOnly = (root, q, cb) ->
                cb.greaterThanOrEqualTo(root.get("eventDate"), Instant.now());
        Page<Event> result = eventRepository.findAll(upcomingOnly, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(Event::getTitle).containsExactly("Future Gig");
    }

    @Test
    void shouldLoadPricingTiersEagerlyWithEntityGraph() {
        Event event = publishedEvent(EventCategory.CONCERT, "Priced Show", Instant.now().plusSeconds(86400));
        eventRepository.saveAndFlush(event);

        EventPricingTier tier = EventPricingTier.builder()
                .event(event)
                .sectionId(UUID.randomUUID())
                .categoryName("VIP")
                .price(new BigDecimal("199.00"))
                .currency("USD")
                .build();
        pricingTierRepository.saveAndFlush(tier);

        entityManager.clear();

        Event loaded = eventRepository.findWithPricingTiersById(event.getId()).orElseThrow();
        assertThat(loaded.getPricingTiers()).hasSize(1);
        assertThat(loaded.getPricingTiers().getFirst().getCategoryName()).isEqualTo("VIP");
    }

    @Test
    void shouldCascadeDeletePricingTiersWhenEventRemoved() {
        Event event = publishedEvent(EventCategory.CONFERENCE, "Conf", Instant.now().plusSeconds(86400));
        event.setPricingTiers(List.of(
                EventPricingTier.builder()
                        .event(event)
                        .sectionId(UUID.randomUUID())
                        .categoryName("GA")
                        .price(new BigDecimal("10.00"))
                        .currency("USD")
                        .build()));
        Event saved = eventRepository.saveAndFlush(event);
        UUID eventId = saved.getId();
        assertThat(pricingTierRepository.existsByEvent_Id(eventId)).isTrue();

        eventRepository.deleteById(eventId);
        eventRepository.flush();

        assertThat(eventRepository.findById(eventId)).isEmpty();
        assertThat(pricingTierRepository.existsByEvent_Id(eventId)).isFalse();
    }
}
