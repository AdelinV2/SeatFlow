package com.seatflow.event.service.impl;

import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.events.DomainEvent;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.event.client.SeatMapClient;
import com.seatflow.event.client.SeatMapVenueLayout;
import com.seatflow.event.client.SeatMapVenueSection;
import com.seatflow.event.client.SeatMapVenueSeat;
import com.seatflow.event.mapper.EventMapper;
import com.seatflow.event.mapper.EventPricingTierMapper;
import com.seatflow.event.messaging.event.EventCancelledEvent;
import com.seatflow.event.messaging.event.EventCompletedEvent;
import com.seatflow.event.messaging.event.EventCreatedEvent;
import com.seatflow.event.messaging.event.EventPublishedEvent;
import com.seatflow.event.model.entity.Event;
import com.seatflow.event.model.entity.OutboxEvent;
import com.seatflow.event.model.enums.EventCategory;
import com.seatflow.event.model.enums.EventStatus;
import com.seatflow.event.repository.EventPricingTierRepository;
import com.seatflow.event.repository.EventRepository;
import com.seatflow.event.repository.OutboxEventRepository;
import com.seatflow.event.repository.projection.EventPriceRangeSummaryProjection;
import com.seatflow.event.service.EventService;
import com.seatflow.event.web.dto.request.CreateEventRequest;
import com.seatflow.event.web.dto.request.UpdateEventRequest;
import com.seatflow.event.web.dto.response.EventDetailResponse;
import com.seatflow.event.web.dto.response.EventSeatMapResponse;
import com.seatflow.event.web.dto.response.EventSummaryResponse;
import com.seatflow.event.web.dto.response.PricingTierResponse;
import com.seatflow.event.web.dto.response.SeatMapSectionResponse;
import com.seatflow.event.web.dto.response.SeatMapSeatResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventServiceImpl implements EventService {

    private static final String EVENT_CREATED = "EVENT_CREATED";
    private static final String EVENT_PUBLISHED = "EVENT_PUBLISHED";
    private static final String EVENT_CANCELLED = "EVENT_CANCELLED";
    private static final String EVENT_COMPLETED = "EVENT_COMPLETED";

    private final EventRepository eventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final EventPricingTierRepository pricingTierRepository;
    private final EventMapper eventMapper;
    private final EventPricingTierMapper tierMapper;
    private final SeatMapClient seatMapClient;
    private final ObjectMapper objectMapper;

    @Autowired
    @Lazy
    private EventServiceImpl self;

    private EventServiceImpl proxy() {
        return self != null ? self : this;
    }

    @Override
    public EventDetailResponse createEvent(CreateEventRequest request) {
        if (!venueExists(request.venueId())) {
            throw new ValidationException("Referenced venue does not exist", ErrorCode.INVALID_REQUEST);
        }
        return proxy().createEventInTx(request);
    }

    @Transactional
    public EventDetailResponse createEventInTx(CreateEventRequest request) {
        Event event = eventMapper.toEntity(request);
        event.setStatus(EventStatus.DRAFT);
        Event saved = eventRepository.save(event);
        publishOutbox(EVENT_CREATED, saved.getId(),
                new EventCreatedEvent(saved.getId(), saved.getVenueId(), saved.getTitle(),
                        saved.getCategory(), saved.getEventDate(), Instant.now()));
        log.info("Event draft created. eventId={}, venueId={}", saved.getId(), saved.getVenueId());
        return eventMapper.toDetailResponse(saved);
    }

    @Override
    public EventDetailResponse updateEvent(UUID eventId, UpdateEventRequest request) {
        Event snapshot = eventRepository.findWithPricingTiersById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));
        if (snapshot.getStatus() == EventStatus.CANCELLED || snapshot.getStatus() == EventStatus.COMPLETED) {
            throw new ValidationException("Event is immutable in its current lifecycle state", ErrorCode.INVALID_REQUEST);
        }
        boolean publishRequested = request.status() != null && request.status() == EventStatus.PUBLISHED
                && snapshot.getStatus() != EventStatus.PUBLISHED;
        if (publishRequested && !venueExists(snapshot.getVenueId())) {
            throw new ValidationException("Referenced venue does not exist", ErrorCode.INVALID_REQUEST);
        }
        return proxy().updateEventInTx(eventId, request);
    }

    @Transactional
    public EventDetailResponse updateEventInTx(UUID eventId, UpdateEventRequest request) {
        Event event = eventRepository.findWithPricingTiersById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));
        boolean noChange = request.title() == null && request.description() == null && request.category() == null
                && request.bannerUrl() == null && request.eventDate() == null && request.status() == null;
        if (noChange) {
            throw new ValidationException("No updatable fields provided", ErrorCode.INVALID_REQUEST);
        }

        EventStatus current = event.getStatus();
        EventStatus requested = request.status();
        if (requested != null && requested != current) {
            applyTransition(current, requested, event);
            event.setStatus(requested);
        }

        eventMapper.updateEntity(request, event);
        event.setUpdatedAt(Instant.now());
        Event saved = eventRepository.save(event);
        return eventMapper.toDetailResponse(saved);
    }

    private void applyTransition(EventStatus current, EventStatus requested, Event event) {
        switch (current) {
            case DRAFT -> {
                if (requested == EventStatus.PUBLISHED) {
                    if (!pricingTierRepository.existsByEvent_Id(event.getId())) {
                        throw new ValidationException("Cannot publish an event without pricing tiers", ErrorCode.INVALID_REQUEST);
                    }
                    publishOutbox(EVENT_PUBLISHED, event.getId(),
                            new EventPublishedEvent(event.getId(), event.getVenueId(), event.getTitle(),
                                    event.getCategory(), event.getEventDate(), Instant.now()));
                } else if (requested == EventStatus.CANCELLED) {
                    publishOutbox(EVENT_CANCELLED, event.getId(),
                            new EventCancelledEvent(event.getId(), event.getVenueId(), event.getTitle(),
                                    event.getEventDate(), Instant.now()));
                } else {
                    throw new ValidationException("Illegal status transition", ErrorCode.INVALID_REQUEST);
                }
            }
            case PUBLISHED -> {
                if (requested == EventStatus.CANCELLED) {
                    publishOutbox(EVENT_CANCELLED, event.getId(),
                            new EventCancelledEvent(event.getId(), event.getVenueId(), event.getTitle(),
                                    event.getEventDate(), Instant.now()));
                } else if (requested == EventStatus.COMPLETED) {
                    publishOutbox(EVENT_COMPLETED, event.getId(),
                            new EventCompletedEvent(event.getId(), event.getVenueId(), event.getTitle(),
                                    event.getEventDate(), Instant.now()));
                } else {
                    throw new ValidationException("Illegal status transition", ErrorCode.INVALID_REQUEST);
                }
            }
            default -> throw new ValidationException("Illegal status transition", ErrorCode.INVALID_REQUEST);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResult<EventSummaryResponse> findPublishedEvents(EventCategory category, String search, Pageable pageable) {
        Specification<Event> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), EventStatus.PUBLISHED));
            predicates.add(cb.greaterThan(root.get("eventDate"), Instant.now()));
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (search != null && !search.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("title")), "%" + search.toLowerCase() + "%"));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<Event> page = eventRepository.findAll(spec, pageable);
        List<UUID> ids = page.getContent().stream().map(Event::getId).toList();
        Map<UUID, EventPriceRangeSummaryProjection> ranges = ids.isEmpty() ? Map.of()
                : pricingTierRepository.findPriceRangesByEventIds(ids).stream()
                        .collect(Collectors.toMap(EventPriceRangeSummaryProjection::getEventId, Function.identity()));
        List<EventSummaryResponse> content = page.getContent().stream().map(e -> {
            EventPriceRangeSummaryProjection r = ranges.get(e.getId());
            BigDecimal min = r == null ? null : r.getMinPrice();
            BigDecimal max = r == null ? null : r.getMaxPrice();
            String currency = r == null ? null : r.getCurrency();
            return eventMapper.toSummaryResponse(e, min, max, currency);
        }).toList();
        return PagedResult.of(content, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public EventDetailResponse getPublishedEvent(UUID eventId) {
        Event event = eventRepository.findWithPricingTiersById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));
        if (event.getStatus() != EventStatus.PUBLISHED || !event.getEventDate().isAfter(Instant.now())) {
            throw new ResourceNotFoundException("Event", eventId);
        }
        return eventMapper.toDetailResponse(event);
    }

    @Override
    @Transactional(readOnly = true)
    public EventDetailResponse getEventForAdministration(UUID eventId) {
        Event event = eventRepository.findWithPricingTiersById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));
        return eventMapper.toDetailResponse(event);
    }

    @Override
    public EventSeatMapResponse getEventSeatMap(UUID eventId) {
        Event event = eventRepository.findWithPricingTiersById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));
        if (event.getStatus() != EventStatus.PUBLISHED || !event.getEventDate().isAfter(Instant.now())) {
            throw new ResourceNotFoundException("Event", eventId);
        }
        SeatMapVenueLayout venue = seatMapClient.getVenueLayout(event.getVenueId());
        List<SeatMapVenueSection> sections = venue.sections() == null ? List.of() : venue.sections();
        return proxy().getEventSeatMapInTx(event, sections, venue);
    }

    @Transactional(readOnly = true)
    public EventSeatMapResponse getEventSeatMapInTx(Event event, List<SeatMapVenueSection> sections,
                                                   SeatMapVenueLayout venue) {
        List<PricingTierResponse> allTiers = event.getPricingTiers().stream()
                .map(tierMapper::toResponse).toList();
        List<SeatMapSectionResponse> mapped = sections.stream().map(vs -> {
            List<PricingTierResponse> sectionTiers = allTiers.stream()
                    .filter(t -> t.sectionId().equals(vs.sectionId())).toList();
            List<SeatMapSeatResponse> seats = vs.seats() == null ? List.of()
                    : vs.seats().stream().map(this::toSeatMapSeat).toList();
            return new SeatMapSectionResponse(vs.sectionId(), vs.name(), vs.rowCount(), vs.colCount(), seats, sectionTiers);
        }).toList();
        long totalConfiguredSeats = venue.totalConfiguredSeats() != null ? venue.totalConfiguredSeats() : 0L;
        return new EventSeatMapResponse(event.getId(), event.getVenueId(), event.getTitle(), event.getEventDate(),
                venue.name(), venue.capacity(), totalConfiguredSeats, mapped);
    }

    private SeatMapSeatResponse toSeatMapSeat(SeatMapVenueSeat vs) {
        return new SeatMapSeatResponse(vs.seatId(), vs.rowLabel(), vs.seatNumber(), vs.gridX(), vs.gridY(), vs.isActive());
    }

    @Override
    @Transactional
    public int completeExpiredEvents(Instant now, int batchSize) {
        List<Event> expired = eventRepository.findPublishedExpiredForUpdate(now, PageRequest.of(0, batchSize));
        for (Event event : expired) {
            event.setStatus(EventStatus.COMPLETED);
            event.setUpdatedAt(now);
            publishOutbox(EVENT_COMPLETED, event.getId(),
                    new EventCompletedEvent(event.getId(), event.getVenueId(), event.getTitle(),
                            event.getEventDate(), now));
        }
        return expired.size();
    }

    private boolean venueExists(UUID venueId) {
        try {
            return seatMapClient.venueExists(venueId);
        } catch (Exception e) {
            throw new BusinessException("Venue validation service unavailable", ErrorCode.INTERNAL_SERVER_ERROR, 500);
        }
    }

    private void publishOutbox(String eventType, UUID aggregateId, DomainEvent domainEvent) {
        String correlationId = CorrelationContext.getCorrelationId().orElse(null);
        EventEnvelope<? extends DomainEvent> envelope = EventEnvelope.of(eventType, aggregateId.toString(), correlationId, domainEvent);
        Map<String, Object> payload = objectMapper.convertValue(envelope, new TypeReference<Map<String, Object>>() {});
        outboxEventRepository.save(OutboxEvent.builder()
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payload)
                .build());
    }
}
