package com.seatflow.event.repository;

import com.seatflow.event.model.entity.Event;
import com.seatflow.event.model.entity.EventPricingTier;
import com.seatflow.event.model.enums.EventCategory;
import com.seatflow.event.model.enums.EventStatus;
import com.seatflow.event.repository.projection.EventPriceRangeSummaryProjection;
import com.seatflow.event.repository.projection.PriceRangeProjection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class EventPricingTierRepositoryTest {

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

    private Event persistedEvent() {
        return eventRepository.saveAndFlush(Event.builder()
                .venueId(UUID.randomUUID())
                .title("Priced Event")
                .description("desc")
                .category(EventCategory.CONCERT)
                .eventDate(Instant.now().plusSeconds(86400))
                .status(EventStatus.PUBLISHED)
                .build());
    }

    private EventPricingTier tier(Event event, UUID sectionId, String categoryName, String price, String currency) {
        return EventPricingTier.builder()
                .event(event)
                .sectionId(sectionId)
                .categoryName(categoryName)
                .price(new BigDecimal(price))
                .currency(currency)
                .build();
    }

    @Test
    void shouldOrderTiersByPriceAscending() {
        Event event = persistedEvent();
        UUID section = UUID.randomUUID();
        pricingTierRepository.saveAllAndFlush(List.of(
                tier(event, section, "VIP", "199.00", "USD"),
                tier(event, section, "GA", "49.00", "USD"),
                tier(event, section, "BACK", "29.00", "USD")
        ));

        List<EventPricingTier> tiers = pricingTierRepository.findByEvent_IdOrderByPriceAsc(event.getId());

        assertThat(tiers).extracting(EventPricingTier::getPrice)
                .containsExactly(new BigDecimal("29.00"), new BigDecimal("49.00"), new BigDecimal("199.00"));
        assertThat(tiers).extracting(EventPricingTier::getCategoryName)
                .containsExactly("BACK", "GA", "VIP");
    }

    @Test
    void shouldReportExistenceByEvent() {
        Event event = persistedEvent();
        assertThat(pricingTierRepository.existsByEvent_Id(event.getId())).isFalse();

        pricingTierRepository.saveAndFlush(tier(event, UUID.randomUUID(), "GA", "10.00", "USD"));
        assertThat(pricingTierRepository.existsByEvent_Id(event.getId())).isTrue();
    }

    @Test
    void shouldFindTierByEventSectionAndCategory() {
        Event event = persistedEvent();
        UUID section = UUID.randomUUID();
        pricingTierRepository.saveAndFlush(tier(event, section, "VIP", "199.00", "USD"));

        Optional<EventPricingTier> found =
                pricingTierRepository.findByEvent_IdAndSectionIdAndCategoryName(event.getId(), section, "VIP");
        assertThat(found).isPresent();
        assertThat(found.get().getPrice()).isEqualByComparingTo("199.00");
    }

    @Test
    void shouldComputePriceRangeForSingleEvent() {
        Event event = persistedEvent();
        UUID section = UUID.randomUUID();
        pricingTierRepository.saveAllAndFlush(List.of(
                tier(event, section, "VIP", "199.00", "USD"),
                tier(event, section, "GA", "49.00", "USD")
        ));

        PriceRangeProjection range = pricingTierRepository.findPriceRangeByEventId(event.getId());
        assertThat(range.getMinPrice()).isEqualByComparingTo("49.00");
        assertThat(range.getMaxPrice()).isEqualByComparingTo("199.00");
        assertThat(range.getCurrency()).isEqualTo("USD");
    }

    @Test
    void shouldComputePriceRangesForMultipleEvents() {
        Event e1 = persistedEvent();
        Event e2 = persistedEvent();
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        pricingTierRepository.saveAllAndFlush(List.of(
                tier(e1, s1, "GA", "40.00", "USD"),
                tier(e1, s1, "VIP", "80.00", "USD"),
                tier(e2, s2, "GA", "120.00", "EUR")
        ));

        List<EventPriceRangeSummaryProjection> ranges =
                pricingTierRepository.findPriceRangesByEventIds(List.of(e1.getId(), e2.getId()));

        assertThat(ranges).hasSize(2);
        assertThat(ranges).extracting(EventPriceRangeSummaryProjection::getEventId)
                .containsExactlyInAnyOrder(e1.getId(), e2.getId());
        assertThat(ranges).extracting(EventPriceRangeSummaryProjection::getCurrency)
                .containsExactlyInAnyOrder("USD", "EUR");
    }

    @Test
    void shouldRejectDuplicateSectionCategoryTier() {
        Event event = persistedEvent();
        UUID section = UUID.randomUUID();
        pricingTierRepository.saveAndFlush(tier(event, section, "VIP", "199.00", "USD"));

        EventPricingTier duplicate = tier(event, section, "VIP", "250.00", "USD");
        assertThatThrownBy(() -> pricingTierRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
