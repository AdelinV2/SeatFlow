package com.seatflow.event.mapper;

import com.seatflow.event.model.entity.Event;
import com.seatflow.event.model.entity.EventPricingTier;
import com.seatflow.event.model.enums.EventCategory;
import com.seatflow.event.model.enums.EventStatus;
import com.seatflow.event.web.dto.request.CreateEventRequest;
import com.seatflow.event.web.dto.request.PricingTierItemRequest;
import com.seatflow.event.web.dto.request.UpdateEventRequest;
import com.seatflow.event.web.dto.response.EventDetailResponse;
import com.seatflow.event.web.dto.response.EventSummaryResponse;
import com.seatflow.event.web.dto.response.PricingTierResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class EventMapperTest {

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

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private EventMapper eventMapper;

    @Test
    void shouldMapCreateRequestToEntityWithDraftStatus() {
        CreateEventRequest request = new CreateEventRequest(
                UUID.randomUUID(), "Hamlet", "A tragedy", EventCategory.THEATRE,
                "https://cdn.example.com/hamlet.png", Instant.now().plusSeconds(86400));

        Event event = eventMapper.toEntity(request);

        assertThat(event.getVenueId()).isEqualTo(request.venueId());
        assertThat(event.getTitle()).isEqualTo("Hamlet");
        assertThat(event.getDescription()).isEqualTo("A tragedy");
        assertThat(event.getCategory()).isEqualTo(EventCategory.THEATRE);
        assertThat(event.getBannerUrl()).isEqualTo(request.bannerUrl());
        assertThat(event.getEventDate()).isEqualTo(request.eventDate());
        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(event.getId()).isNull();
        assertThat(event.getVersion()).isNull();
        assertThat(event.getPricingTiers()).isEmpty();
    }

    @Test
    void shouldMapEntityToDetailResponseIncludingTiers() {
        EventPricingTier tier = EventPricingTier.builder()
                .id(UUID.randomUUID())
                .sectionId(UUID.randomUUID())
                .categoryName("VIP")
                .price(new BigDecimal("199.00"))
                .currency("USD")
                .build();
        Event event = Event.builder()
                .id(UUID.randomUUID())
                .venueId(UUID.randomUUID())
                .title("Hamlet")
                .description("A tragedy")
                .category(EventCategory.THEATRE)
                .bannerUrl("https://cdn.example.com/hamlet.png")
                .eventDate(Instant.now().plusSeconds(86400))
                .status(EventStatus.PUBLISHED)
                .pricingTiers(List.of(tier))
                .build();

        EventDetailResponse response = eventMapper.toDetailResponse(event);

        assertThat(response.id()).isEqualTo(event.getId());
        assertThat(response.venueId()).isEqualTo(event.getVenueId());
        assertThat(response.title()).isEqualTo("Hamlet");
        assertThat(response.category()).isEqualTo(EventCategory.THEATRE);
        assertThat(response.status()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(response.pricingTiers()).hasSize(1);
        assertThat(response.pricingTiers().getFirst().categoryName()).isEqualTo("VIP");
        assertThat(response.pricingTiers().getFirst().price()).isEqualByComparingTo("199.00");
    }

    @Test
    void shouldMapEntityAndPriceRangeToSummaryResponse() {
        Event event = Event.builder()
                .id(UUID.randomUUID())
                .venueId(UUID.randomUUID())
                .title("Concert")
                .category(EventCategory.CONCERT)
                .bannerUrl("https://cdn.example.com/c.png")
                .eventDate(Instant.now().plusSeconds(86400))
                .status(EventStatus.PUBLISHED)
                .build();

        EventSummaryResponse response = eventMapper.toSummaryResponse(
                event, new BigDecimal("29.00"), new BigDecimal("199.00"), "USD");

        assertThat(response.id()).isEqualTo(event.getId());
        assertThat(response.title()).isEqualTo("Concert");
        assertThat(response.category()).isEqualTo(EventCategory.CONCERT);
        assertThat(response.minPrice()).isEqualByComparingTo("29.00");
        assertThat(response.maxPrice()).isEqualByComparingTo("199.00");
        assertThat(response.currency()).isEqualTo("USD");
    }

    @Test
    void shouldPreserveExistingValuesOnPartialUpdate() {
        Event event = Event.builder()
                .id(UUID.randomUUID())
                .venueId(UUID.randomUUID())
                .title("Original Title")
                .description("Original description")
                .category(EventCategory.CONFERENCE)
                .bannerUrl("https://cdn.example.com/a.png")
                .eventDate(Instant.now().plusSeconds(86400))
                .status(EventStatus.PUBLISHED)
                .build();

        UpdateEventRequest partial = new UpdateEventRequest(
                "Updated Title", null, null, null, null, null);

        eventMapper.updateEntity(partial, event);

        assertThat(event.getTitle()).isEqualTo("Updated Title");
        assertThat(event.getDescription()).isEqualTo("Original description");
        assertThat(event.getCategory()).isEqualTo(EventCategory.CONFERENCE);
        assertThat(event.getBannerUrl()).isEqualTo("https://cdn.example.com/a.png");
        assertThat(event.getStatus()).isEqualTo(EventStatus.PUBLISHED);
    }

    @Test
    void shouldApplyNonNullFieldsOnFullUpdate() {
        Event event = Event.builder()
                .id(UUID.randomUUID())
                .venueId(UUID.randomUUID())
                .title("Original Title")
                .description("Original description")
                .category(EventCategory.CONFERENCE)
                .bannerUrl("https://cdn.example.com/a.png")
                .eventDate(Instant.now().plusSeconds(86400))
                .status(EventStatus.PUBLISHED)
                .build();

        UpdateEventRequest full = new UpdateEventRequest(
                "New Title", "New description", EventCategory.SPORTS,
                "https://cdn.example.com/b.png", Instant.now().plusSeconds(172800), EventStatus.CANCELLED);

        eventMapper.updateEntity(full, event);

        assertThat(event.getTitle()).isEqualTo("New Title");
        assertThat(event.getDescription()).isEqualTo("New description");
        assertThat(event.getCategory()).isEqualTo(EventCategory.SPORTS);
        assertThat(event.getBannerUrl()).isEqualTo("https://cdn.example.com/b.png");
        assertThat(event.getStatus()).isEqualTo(EventStatus.CANCELLED);
    }
}
