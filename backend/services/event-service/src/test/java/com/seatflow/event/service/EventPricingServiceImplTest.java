package com.seatflow.event.service;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.event.client.SeatMapClient;
import com.seatflow.event.client.VenueSeatMapResponse;
import com.seatflow.event.client.VenueSectionResponse;
import com.seatflow.event.mapper.EventPricingTierMapper;
import com.seatflow.event.model.common.EventPriceRange;
import com.seatflow.event.model.entity.Event;
import com.seatflow.event.model.entity.EventPricingTier;
import com.seatflow.event.model.enums.EventStatus;
import com.seatflow.event.repository.EventPricingTierRepository;
import com.seatflow.event.repository.EventRepository;
import com.seatflow.event.repository.projection.PriceRangeProjection;
import com.seatflow.event.service.impl.EventPricingServiceImpl;
import com.seatflow.event.web.dto.request.ConfigurePricingRequest;
import com.seatflow.event.web.dto.request.PricingTierItemRequest;
import com.seatflow.event.web.dto.response.PricingTierResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventPricingServiceImplTest {

    @Mock
    private EventRepository eventRepository;
    @Mock
    private EventPricingTierRepository pricingTierRepository;
    @Mock
    private EventPricingTierMapper tierMapper;
    @Mock
    private SeatMapClient seatMapClient;

    @InjectMocks
    private EventPricingServiceImpl pricingService;

    private static final UUID VENUE_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID SECTION_1 = UUID.randomUUID();
    private static final UUID SECTION_2 = UUID.randomUUID();

    private Event buildEvent(EventStatus status) {
        return Event.builder()
                .id(EVENT_ID)
                .venueId(VENUE_ID)
                .title("Hamlet")
                .description("A play")
                .category(com.seatflow.event.model.enums.EventCategory.OTHER)
                .eventDate(Instant.now().plusSeconds(86400))
                .status(status)
                .pricingTiers(new ArrayList<>())
                .build();
    }

    private PricingTierItemRequest tier(UUID sectionId, String category, BigDecimal price, String currency) {
        return new PricingTierItemRequest(sectionId, category, price, currency);
    }

    @Test
    void configurePricing_validReplacement_returnsPriceAscending() {
        Event event = buildEvent(EventStatus.PUBLISHED);
        ConfigurePricingRequest request = new ConfigurePricingRequest(List.of(
                tier(SECTION_1, "VIP", new BigDecimal("50.00"), "USD"),
                tier(SECTION_2, "GEN", new BigDecimal("30.00"), "USD")));
        when(eventRepository.findWithPricingTiersById(EVENT_ID)).thenReturn(Optional.of(event));
        when(seatMapClient.getVenueSeatMap(VENUE_ID)).thenReturn(
                new VenueSeatMapResponse(VENUE_ID, "Venue", 1000, List.of(
                        new VenueSectionResponse(SECTION_1, "VIP", 10, 20, null, List.of()),
                        new VenueSectionResponse(SECTION_2, "GEN", 10, 20, null, List.of()))));
        when(eventRepository.save(any(Event.class))).thenReturn(event);
        when(pricingTierRepository.findByEvent_IdOrderByPriceAsc(EVENT_ID)).thenReturn(List.of(
                EventPricingTier.builder().id(UUID.randomUUID()).sectionId(SECTION_1).categoryName("VIP")
                        .price(new BigDecimal("50.00")).currency("USD").build(),
                EventPricingTier.builder().id(UUID.randomUUID()).sectionId(SECTION_2).categoryName("GEN")
                        .price(new BigDecimal("30.00")).currency("USD").build()));
        when(tierMapper.toResponse(any(EventPricingTier.class)))
                .thenReturn(new PricingTierResponse(UUID.randomUUID(), SECTION_2, "GEN", new BigDecimal("30.00"), "USD"),
                        new PricingTierResponse(UUID.randomUUID(), SECTION_1, "VIP", new BigDecimal("50.00"), "USD"));

        List<PricingTierResponse> result = pricingService.configurePricing(EVENT_ID, request);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).price()).isEqualByComparingTo("30.00");
        assertThat(result.get(1).price()).isEqualByComparingTo("50.00");
        verify(pricingTierRepository).flush();
    }

    @Test
    void configurePricing_duplicateTier_rejects() {
        Event event = buildEvent(EventStatus.PUBLISHED);
        ConfigurePricingRequest request = new ConfigurePricingRequest(List.of(
                tier(SECTION_1, "VIP", new BigDecimal("50.00"), "USD"),
                tier(SECTION_1, "VIP", new BigDecimal("60.00"), "USD")));
        when(eventRepository.findWithPricingTiersById(EVENT_ID)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> pricingService.configurePricing(EVENT_ID, request))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void configurePricing_mixedCurrency_rejects() {
        Event event = buildEvent(EventStatus.PUBLISHED);
        ConfigurePricingRequest request = new ConfigurePricingRequest(List.of(
                tier(SECTION_1, "VIP", new BigDecimal("50.00"), "USD"),
                tier(SECTION_2, "GEN", new BigDecimal("40.00"), "EUR")));
        when(eventRepository.findWithPricingTiersById(EVENT_ID)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> pricingService.configurePricing(EVENT_ID, request))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void configurePricing_foreignSection_rejects() {
        Event event = buildEvent(EventStatus.PUBLISHED);
        ConfigurePricingRequest request = new ConfigurePricingRequest(List.of(
                tier(SECTION_1, "VIP", new BigDecimal("50.00"), "USD")));
        when(eventRepository.findWithPricingTiersById(EVENT_ID)).thenReturn(Optional.of(event));
        when(seatMapClient.getVenueSeatMap(VENUE_ID)).thenReturn(
                new VenueSeatMapResponse(VENUE_ID, "Venue", 1000, List.of(
                        new VenueSectionResponse(SECTION_2, "GEN", 10, 20, null, List.of()))));

        assertThatThrownBy(() -> pricingService.configurePricing(EVENT_ID, request))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void configurePricing_terminalEvent_rejects() {
        Event event = buildEvent(EventStatus.CANCELLED);
        ConfigurePricingRequest request = new ConfigurePricingRequest(List.of(
                tier(SECTION_1, "VIP", new BigDecimal("50.00"), "USD")));
        when(eventRepository.findWithPricingTiersById(EVENT_ID)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> pricingService.configurePricing(EVENT_ID, request))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void configurePricing_sectionValidationOutage_failsClosed() {
        Event event = buildEvent(EventStatus.PUBLISHED);
        ConfigurePricingRequest request = new ConfigurePricingRequest(List.of(
                tier(SECTION_1, "VIP", new BigDecimal("50.00"), "USD")));
        when(eventRepository.findWithPricingTiersById(EVENT_ID)).thenReturn(Optional.of(event));
        when(seatMapClient.getVenueSeatMap(VENUE_ID)).thenThrow(new RuntimeException("outage"));

        assertThatThrownBy(() -> pricingService.configurePricing(EVENT_ID, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Test
    void getPriceRange_nullProjection_returnsNulls() {
        when(pricingTierRepository.findPriceRangeByEventId(EVENT_ID)).thenReturn(null);

        EventPriceRange range = pricingService.getPriceRange(EVENT_ID);

        assertThat(range.minPrice()).isNull();
        assertThat(range.maxPrice()).isNull();
        assertThat(range.currency()).isNull();
    }

    @Test
    void getPriceRange_calculatesMinMax() {
        when(pricingTierRepository.findPriceRangeByEventId(EVENT_ID))
                .thenReturn(new RangeProj(new BigDecimal("29.00"), new BigDecimal("199.00"), "USD"));

        EventPriceRange range = pricingService.getPriceRange(EVENT_ID);

        assertThat(range.minPrice()).isEqualByComparingTo("29.00");
        assertThat(range.maxPrice()).isEqualByComparingTo("199.00");
        assertThat(range.currency()).isEqualTo("USD");
    }

    @Test
    void getPricingTiers_missingEvent_throws() {
        when(eventRepository.existsById(EVENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> pricingService.getPricingTiers(EVENT_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static class RangeProj implements PriceRangeProjection {
        private final BigDecimal minPrice;
        private final BigDecimal maxPrice;
        private final String currency;

        RangeProj(BigDecimal minPrice, BigDecimal maxPrice, String currency) {
            this.minPrice = minPrice;
            this.maxPrice = maxPrice;
            this.currency = currency;
        }

        @Override
        public BigDecimal getMinPrice() {
            return minPrice;
        }

        @Override
        public BigDecimal getMaxPrice() {
            return maxPrice;
        }

        @Override
        public String getCurrency() {
            return currency;
        }
    }
}
