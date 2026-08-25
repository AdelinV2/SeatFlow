package com.seatflow.event.mapper;

import com.seatflow.event.model.entity.EventPricingTier;
import com.seatflow.event.web.dto.request.PricingTierItemRequest;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class EventPricingTierMapperTest {

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
    private EventPricingTierMapper eventPricingTierMapper;

    @Test
    void shouldMapItemRequestToEntity() {
        PricingTierItemRequest request = new PricingTierItemRequest(
                UUID.randomUUID(), "VIP", new BigDecimal("199.00"), "USD");

        EventPricingTier tier = eventPricingTierMapper.toEntity(request);

        assertThat(tier.getSectionId()).isEqualTo(request.sectionId());
        assertThat(tier.getCategoryName()).isEqualTo("VIP");
        assertThat(tier.getPrice()).isEqualByComparingTo("199.00");
        assertThat(tier.getCurrency()).isEqualTo("USD");
        assertThat(tier.getId()).isNull();
        assertThat(tier.getEvent()).isNull();
    }

    @Test
    void shouldMapEntityToResponse() {
        EventPricingTier tier = EventPricingTier.builder()
                .id(UUID.randomUUID())
                .sectionId(UUID.randomUUID())
                .categoryName("GA")
                .price(new BigDecimal("49.00"))
                .currency("USD")
                .build();

        PricingTierResponse response = eventPricingTierMapper.toResponse(tier);

        assertThat(response.id()).isEqualTo(tier.getId());
        assertThat(response.sectionId()).isEqualTo(tier.getSectionId());
        assertThat(response.categoryName()).isEqualTo("GA");
        assertThat(response.price()).isEqualByComparingTo("49.00");
        assertThat(response.currency()).isEqualTo("USD");
    }
}
