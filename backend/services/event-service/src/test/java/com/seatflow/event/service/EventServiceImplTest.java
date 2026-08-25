package com.seatflow.event.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.event.client.SeatMapClient;
import com.seatflow.event.client.SeatMapVenueLayout;
import com.seatflow.event.client.SeatMapVenueSection;
import com.seatflow.event.client.SeatMapVenueSeat;
import com.seatflow.event.mapper.EventMapper;
import com.seatflow.event.mapper.EventPricingTierMapper;
import com.seatflow.event.model.entity.Event;
import com.seatflow.event.model.entity.EventPricingTier;
import com.seatflow.event.model.entity.OutboxEvent;
import com.seatflow.event.model.enums.EventCategory;
import com.seatflow.event.model.enums.EventStatus;
import com.seatflow.event.repository.EventPricingTierRepository;
import com.seatflow.event.repository.EventRepository;
import com.seatflow.event.repository.OutboxEventRepository;
import com.seatflow.event.repository.projection.EventPriceRangeSummaryProjection;
import com.seatflow.event.service.impl.EventServiceImpl;
import com.seatflow.event.web.dto.request.CreateEventRequest;
import com.seatflow.event.web.dto.request.UpdateEventRequest;
import com.seatflow.event.web.dto.response.EventDetailResponse;
import com.seatflow.event.web.dto.response.EventSeatMapResponse;
import com.seatflow.event.web.dto.response.EventSummaryResponse;
import com.seatflow.event.web.dto.response.PricingTierResponse;
import com.seatflow.event.web.dto.response.SeatMapSectionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventServiceImplTest {

    @Mock
    private EventRepository eventRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private EventPricingTierRepository pricingTierRepository;
    @Mock
    private EventMapper eventMapper;
    @Mock
    private EventPricingTierMapper tierMapper;
    @Mock
    private SeatMapClient seatMapClient;
    @Spy
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @InjectMocks
    private EventServiceImpl eventService;

    private static final UUID VENUE_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID SECTION_ID = UUID.randomUUID();

    private Event buildEvent(EventStatus status) {
        return Event.builder()
                .id(EVENT_ID)
                .venueId(VENUE_ID)
                .title("Hamlet")
                .description("A play")
                .category(EventCategory.OTHER)
                .eventDate(Instant.now().plusSeconds(86400))
                .status(status)
                .build();
    }

    private EventDetailResponse dummyDetail() {
        return new EventDetailResponse(EVENT_ID, VENUE_ID, "Hamlet", "desc", EventCategory.OTHER,
                null, Instant.now(), EventStatus.DRAFT, List.of(), Instant.now(), Instant.now());
    }

    @Test
    void createEvent_persistsDraftAndPublishesCreatedEvent() {
        CreateEventRequest request = new CreateEventRequest(VENUE_ID, "Hamlet", "desc",
                EventCategory.CONCERT, "https://cdn.example.com/h.png", Instant.now().plusSeconds(86400));
        Event draft = buildEvent(EventStatus.DRAFT);
        when(eventMapper.toEntity(request)).thenReturn(draft);
        when(eventRepository.save(any(Event.class))).thenReturn(draft);
        when(eventMapper.toDetailResponse(any(Event.class))).thenReturn(dummyDetail());
        when(seatMapClient.venueExists(VENUE_ID)).thenReturn(true);

        EventDetailResponse result = eventService.createEvent(request);

        assertThat(result).isNotNull();
        assertThat(draft.getStatus()).isEqualTo(EventStatus.DRAFT);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("EVENT_CREATED");
        assertThat(captor.getValue().getAggregateId()).isEqualTo(EVENT_ID);
    }

    @Test
    void createEvent_missingVenue_rejects() {
        CreateEventRequest request = new CreateEventRequest(VENUE_ID, "Hamlet", "desc",
                EventCategory.CONCERT, null, Instant.now().plusSeconds(86400));
        when(seatMapClient.venueExists(VENUE_ID)).thenReturn(false);

        assertThatThrownBy(() -> eventService.createEvent(request))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void updateEvent_publishTransition_publishesEventPublished() {
        Event draft = buildEvent(EventStatus.DRAFT);
        when(eventRepository.findWithPricingTiersById(EVENT_ID)).thenReturn(Optional.of(draft));
        when(seatMapClient.venueExists(VENUE_ID)).thenReturn(true);
        when(pricingTierRepository.existsByEvent_Id(EVENT_ID)).thenReturn(true);
        when(eventRepository.save(any(Event.class))).thenReturn(draft);
        when(eventMapper.toDetailResponse(any(Event.class))).thenReturn(dummyDetail());

        eventService.updateEvent(EVENT_ID, new UpdateEventRequest(null, null, null, null, null, EventStatus.PUBLISHED));

        assertThat(draft.getStatus()).isEqualTo(EventStatus.PUBLISHED);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("EVENT_PUBLISHED");
    }

    @Test
    void updateEvent_publishWithoutPricing_rejects() {
        Event draft = buildEvent(EventStatus.DRAFT);
        when(eventRepository.findWithPricingTiersById(EVENT_ID)).thenReturn(Optional.of(draft));
        when(seatMapClient.venueExists(VENUE_ID)).thenReturn(true);
        when(pricingTierRepository.existsByEvent_Id(EVENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> eventService.updateEvent(EVENT_ID,
                new UpdateEventRequest(null, null, null, null, null, EventStatus.PUBLISHED)))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void updateEvent_cancelTransition_publishesEventCancelled() {
        Event draft = buildEvent(EventStatus.DRAFT);
        when(eventRepository.findWithPricingTiersById(EVENT_ID)).thenReturn(Optional.of(draft));
        when(eventRepository.save(any(Event.class))).thenReturn(draft);
        when(eventMapper.toDetailResponse(any(Event.class))).thenReturn(dummyDetail());

        eventService.updateEvent(EVENT_ID, new UpdateEventRequest(null, null, null, null, null, EventStatus.CANCELLED));

        assertThat(draft.getStatus()).isEqualTo(EventStatus.CANCELLED);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("EVENT_CANCELLED");
    }

    @Test
    void updateEvent_completeTransition_publishesEventCompleted() {
        Event published = buildEvent(EventStatus.PUBLISHED);
        when(eventRepository.findWithPricingTiersById(EVENT_ID)).thenReturn(Optional.of(published));
        when(eventRepository.save(any(Event.class))).thenReturn(published);
        when(eventMapper.toDetailResponse(any(Event.class))).thenReturn(dummyDetail());

        eventService.updateEvent(EVENT_ID, new UpdateEventRequest(null, null, null, null, null, EventStatus.COMPLETED));

        assertThat(published.getStatus()).isEqualTo(EventStatus.COMPLETED);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("EVENT_COMPLETED");
    }

    @Test
    void updateEvent_illegalTransition_rejects() {
        Event draft = buildEvent(EventStatus.DRAFT);
        when(eventRepository.findWithPricingTiersById(EVENT_ID)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> eventService.updateEvent(EVENT_ID,
                new UpdateEventRequest(null, null, null, null, null, EventStatus.COMPLETED)))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void updateEvent_terminalEvent_rejects() {
        Event cancelled = buildEvent(EventStatus.CANCELLED);
        when(eventRepository.findWithPricingTiersById(EVENT_ID)).thenReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> eventService.updateEvent(EVENT_ID,
                new UpdateEventRequest("New title", null, null, null, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
    }

    @Test
    void updateEvent_emptyRequest_rejects() {
        Event draft = buildEvent(EventStatus.DRAFT);
        when(eventRepository.findWithPricingTiersById(EVENT_ID)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> eventService.updateEvent(EVENT_ID,
                new UpdateEventRequest(null, null, null, null, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
    }

    @Test
    void findPublishedEvents_mapsPageWithBatchPriceLookup() {
        Event event = buildEvent(EventStatus.PUBLISHED);
        Page<Event> page = new PageImpl<>(List.of(event), PageRequest.of(0, 10), 1);
        when(eventRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        when(pricingTierRepository.findPriceRangesByEventIds(anyList()))
                .thenReturn(List.of(new RangeProj(EVENT_ID, new BigDecimal("29.00"), new BigDecimal("199.00"), "USD")));
        when(eventMapper.toSummaryResponse(any(Event.class), any(), any(), anyString()))
                .thenReturn(new EventSummaryResponse(EVENT_ID, "Hamlet", EventCategory.OTHER, null,
                        Instant.now(), new BigDecimal("29.00"), new BigDecimal("199.00"), "USD"));

        PagedResult<EventSummaryResponse> result = eventService.findPublishedEvents(null, null, PageRequest.of(0, 10));

        assertThat(result.content()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(1);
        verify(pricingTierRepository).findPriceRangesByEventIds(List.of(EVENT_ID));
    }

    @Test
    void getPublishedEvent_hidesDraft() {
        Event draft = buildEvent(EventStatus.DRAFT);
        when(eventRepository.findWithPricingTiersById(EVENT_ID)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> eventService.getPublishedEvent(EVENT_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getPublishedEvent_hidesPastEvents() {
        Event past = buildEvent(EventStatus.PUBLISHED);
        past.setEventDate(Instant.now().minusSeconds(3600));
        when(eventRepository.findWithPricingTiersById(EVENT_ID)).thenReturn(Optional.of(past));

        assertThatThrownBy(() -> eventService.getPublishedEvent(EVENT_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getPublishedEvent_returnsFuturePublishedEvent() {
        Event future = buildEvent(EventStatus.PUBLISHED);
        when(eventRepository.findWithPricingTiersById(EVENT_ID)).thenReturn(Optional.of(future));
        when(eventMapper.toDetailResponse(any(Event.class))).thenReturn(dummyDetail());

        EventDetailResponse result = eventService.getPublishedEvent(EVENT_ID);

        assertThat(result).isNotNull();
    }

    @Test
    void getEventSeatMap_hidesPastEvents() {
        Event past = buildEvent(EventStatus.PUBLISHED);
        past.setEventDate(Instant.now().minusSeconds(3600));
        when(eventRepository.findWithPricingTiersById(EVENT_ID)).thenReturn(Optional.of(past));

        assertThatThrownBy(() -> eventService.getEventSeatMap(EVENT_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void completeExpiredEvents_transitionsToCompletedAndPublishes() {
        Event expired = buildEvent(EventStatus.PUBLISHED);
        expired.setEventDate(Instant.now().minusSeconds(3600));
        when(eventRepository.findPublishedExpiredForUpdate(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(expired));

        int completed = eventService.completeExpiredEvents(Instant.now(), 50);

        assertThat(completed).isEqualTo(1);
        assertThat(expired.getStatus()).isEqualTo(EventStatus.COMPLETED);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("EVENT_COMPLETED");
        assertThat(captor.getValue().getAggregateId()).isEqualTo(EVENT_ID);
    }

    @Test
    void getEventSeatMap_composesSectionsWithOverlaidPrices() {
        Event event = buildEvent(EventStatus.PUBLISHED);
        event.setPricingTiers(List.of(EventPricingTier.builder()
                .id(UUID.randomUUID()).sectionId(SECTION_ID).categoryName("VIP")
                .price(new BigDecimal("50")).currency("USD").build()));
        when(eventRepository.findWithPricingTiersById(EVENT_ID)).thenReturn(Optional.of(event));
        when(tierMapper.toResponse(any(EventPricingTier.class)))
                .thenReturn(new PricingTierResponse(UUID.randomUUID(), SECTION_ID, "VIP", new BigDecimal("50"), "USD"));
        when(seatMapClient.getVenueLayout(VENUE_ID)).thenReturn(new SeatMapVenueLayout(
                VENUE_ID, "Grand Hall", 500, 10L,
                List.of(new SeatMapVenueSection(SECTION_ID, "A", 5, 10,
                        List.of(new SeatMapVenueSeat(UUID.randomUUID(), "R1", 1, 1, 1, true))))));

        EventSeatMapResponse result = eventService.getEventSeatMap(EVENT_ID);

        assertThat(result.sections()).hasSize(1);
        SeatMapSectionResponse section = result.sections().get(0);
        assertThat(section.pricingTiers()).hasSize(1);
        assertThat(section.pricingTiers().get(0).sectionId()).isEqualTo(SECTION_ID);
        assertThat(result.totalConfiguredSeats()).isEqualTo(10L);
        assertThat(result.venueName()).isEqualTo("Grand Hall");
    }

    private static class RangeProj implements EventPriceRangeSummaryProjection {
        private final UUID eventId;
        private final BigDecimal minPrice;
        private final BigDecimal maxPrice;
        private final String currency;

        RangeProj(UUID eventId, BigDecimal minPrice, BigDecimal maxPrice, String currency) {
            this.eventId = eventId;
            this.minPrice = minPrice;
            this.maxPrice = maxPrice;
            this.currency = currency;
        }

        @Override
        public UUID getEventId() {
            return eventId;
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
