package com.seatflow.event.service.impl;

import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.events.DomainEvent;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.common.observability.tracing.W3cTraceContextPropagator;
import com.seatflow.event.client.SeatMapClient;
import com.seatflow.event.client.SeatMapVenueLayout;
import com.seatflow.event.client.SeatMapVenueSection;
import com.seatflow.event.client.SeatMapVenueSeat;
import com.seatflow.event.mapper.EventMapper;
import com.seatflow.event.mapper.EventPricingTierMapper;
import com.seatflow.event.mapper.EventSessionMapper;
import com.seatflow.event.messaging.event.EventCancelledEvent;
import com.seatflow.event.messaging.event.EventCompletedEvent;
import com.seatflow.event.messaging.event.EventCreatedEvent;
import com.seatflow.event.messaging.event.EventPublishedEvent;
import com.seatflow.event.model.entity.Event;
import com.seatflow.event.model.entity.EventSession;
import com.seatflow.event.model.entity.OutboxEvent;
import com.seatflow.event.model.enums.EventCategory;
import com.seatflow.event.model.enums.EventSessionStatus;
import com.seatflow.event.model.enums.EventStatus;
import com.seatflow.event.repository.EventPricingTierRepository;
import com.seatflow.event.repository.EventRepository;
import com.seatflow.event.repository.EventSessionRepository;
import com.seatflow.event.repository.OutboxEventRepository;
import com.seatflow.event.repository.projection.EventPriceRangeSummaryProjection;
import com.seatflow.event.service.EventService;
import com.seatflow.event.web.dto.request.CreateEventRequest;
import com.seatflow.event.web.dto.request.UpdateEventRequest;
import com.seatflow.event.web.dto.response.EventDetailResponse;
import com.seatflow.event.web.dto.response.EventSeatMapResponse;
import com.seatflow.event.web.dto.response.EventSessionResponse;
import com.seatflow.event.web.dto.response.EventSummaryResponse;
import com.seatflow.event.web.dto.response.PricingTierResponse;
import com.seatflow.event.web.dto.response.SeatMapSectionResponse;
import com.seatflow.event.web.dto.response.SeatMapSeatResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final EventSessionRepository eventSessionRepository;
    private final EventMapper eventMapper;
    private final EventPricingTierMapper tierMapper;
    private final EventSessionMapper eventSessionMapper;
    private final SeatMapClient seatMapClient;
    private final ObjectMapper objectMapper;
    private final W3cTraceContextPropagator w3cTraceContextPropagator;

    @Override
    @Transactional
    public EventDetailResponse createEvent(CreateEventRequest request) {
        if (!venueExists(request.venueId())) {
            throw new ValidationException("Referenced venue does not exist", ErrorCode.INVALID_REQUEST);
        }
        Event event = eventMapper.toEntity(request);
        event.setStatus(EventStatus.DRAFT);
        Event saved = eventRepository.save(event);
        publishOutbox(EVENT_CREATED, saved.getId(),
                new EventCreatedEvent(saved.getId(), saved.getVenueId(), saved.getTitle(),
                        saved.getCategory(), Instant.now()));
        log.info("Event draft created. eventId={}, venueId={}", saved.getId(), saved.getVenueId());
        return withSessions(eventMapper.toDetailResponse(saved), saved.getId(), true);
    }

    @Override
    @Transactional
    public EventDetailResponse updateEvent(UUID eventId, UpdateEventRequest request) {
        Event event = eventRepository.findWithPricingTiersById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));
        if (event.getStatus() == EventStatus.CANCELLED || event.getStatus() == EventStatus.COMPLETED) {
            throw new ValidationException("Event is immutable in its current lifecycle state", ErrorCode.INVALID_REQUEST);
        }
        boolean noChange = request.title() == null && request.description() == null && request.category() == null
                && request.bannerUrl() == null && request.status() == null;
        if (noChange) {
            throw new ValidationException("No updatable fields provided", ErrorCode.INVALID_REQUEST);
        }

        boolean publishRequested = request.status() != null && request.status() == EventStatus.PUBLISHED
                && event.getStatus() != EventStatus.PUBLISHED;
        if (publishRequested && !venueExists(event.getVenueId())) {
            throw new ValidationException("Referenced venue does not exist", ErrorCode.INVALID_REQUEST);
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
        return withSessions(eventMapper.toDetailResponse(saved), saved.getId(), true);
    }

    private void applyTransition(EventStatus current, EventStatus requested, Event event) {
        switch (current) {
            case DRAFT -> {
                if (requested == EventStatus.PUBLISHED) {
                    if (!pricingTierRepository.existsByEvent_Id(event.getId())) {
                        throw new ValidationException("Cannot publish an event without pricing tiers", ErrorCode.INVALID_REQUEST);
                    }
                    if (!eventSessionRepository.existsByEvent_IdAndStatusAndStartsAtAfter(
                            event.getId(), EventSessionStatus.SCHEDULED, Instant.now())) {
                        throw new ValidationException(
                                "Cannot publish an event without at least one future scheduled session",
                                ErrorCode.INVALID_REQUEST);
                    }
                    publishOutbox(EVENT_PUBLISHED, event.getId(),
                            new EventPublishedEvent(event.getId(), event.getVenueId(), event.getTitle(),
                                    event.getCategory(), Instant.now()));
                } else if (requested == EventStatus.CANCELLED) {
                    publishOutbox(EVENT_CANCELLED, event.getId(),
                            new EventCancelledEvent(event.getId(), event.getVenueId(), event.getTitle(),
                                    Instant.now()));
                } else {
                    throw new ValidationException("Illegal status transition", ErrorCode.INVALID_REQUEST);
                }
            }
            case PUBLISHED -> {
                if (requested == EventStatus.CANCELLED) {
                    publishOutbox(EVENT_CANCELLED, event.getId(),
                            new EventCancelledEvent(event.getId(), event.getVenueId(), event.getTitle(),
                                    Instant.now()));
                } else if (requested == EventStatus.COMPLETED) {
                    publishOutbox(EVENT_COMPLETED, event.getId(),
                            new EventCompletedEvent(event.getId(), event.getVenueId(), event.getTitle(),
                                    Instant.now()));
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
        // P12-007 (ADR-011): session-aware catalog. Filtering/ordering formerly on
        // Event.eventDate is now derived from the next visible future session
        // (SCHEDULED, startsAt > now, endsAt > now) via event_sessions. The
        // derived instant is display/search metadata, never a booking key, and
        // event rows are never duplicated (one summary per event).
        Instant now = Instant.now();
        Specification<Event> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), EventStatus.PUBLISHED));
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (search != null && !search.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("title")), "%" + search.toLowerCase() + "%"));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        List<Event> candidates = eventRepository.findAll(spec, Pageable.unpaged()).getContent();
        Map<UUID, Instant> nextSessionByEvent = nextVisibleSessionStarts(candidates.stream()
                .map(Event::getId).toList(), now);
        List<Event> visible = candidates.stream()
                .filter(e -> nextSessionByEvent.containsKey(e.getId()))
                .toList();
        List<Event> ordered = applyCatalogOrdering(visible, nextSessionByEvent, pageable);
        int pageNumber = Math.max(pageable.getPageNumber(), 0);
        int pageSize = pageable.getPageSize() <= 0 ? 20 : Math.min(pageable.getPageSize(), 100);
        int total = ordered.size();
        int from = Math.min(pageNumber * pageSize, total);
        int to = Math.min(from + pageSize, total);
        List<Event> pageContent = ordered.subList(from, to);
        List<UUID> ids = pageContent.stream().map(Event::getId).toList();
        Map<UUID, EventPriceRangeSummaryProjection> ranges = ids.isEmpty() ? Map.of()
                : pricingTierRepository.findPriceRangesByEventIds(ids).stream()
                        .collect(Collectors.toMap(EventPriceRangeSummaryProjection::getEventId, Function.identity()));
        List<EventSummaryResponse> content = pageContent.stream().map(e -> {
            EventPriceRangeSummaryProjection r = ranges.get(e.getId());
            BigDecimal min = r == null ? null : r.getMinPrice();
            BigDecimal max = r == null ? null : r.getMaxPrice();
            String currency = r == null ? null : r.getCurrency();
            return eventMapper.toSummaryResponse(e, nextSessionByEvent.get(e.getId()), min, max, currency);
        }).toList();
        return PagedResult.of(content, pageNumber, pageSize, total);
    }

    private Map<UUID, Instant> nextVisibleSessionStarts(List<UUID> eventIds, Instant now) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        List<EventSession> sessions = eventSessionRepository
                .findByEvent_IdInAndStatusAndStartsAtAfterAndEndsAtAfterOrderByStartsAtAscIdAsc(
                        eventIds, EventSessionStatus.SCHEDULED, now, now);
        Map<UUID, Instant> nextByEvent = new java.util.HashMap<>();
        for (EventSession session : sessions) {
            UUID eventId = session.getEvent().getId();
            // Ordered by startsAt ASC, so the first occurrence per event is the next.
            nextByEvent.putIfAbsent(eventId, session.getStartsAt());
        }
        return nextByEvent;
    }

    // P12-007 catalog ordering contract: every branch ends in a total order.
    // Default/session ordering is (nextSessionStartsAt ASC, event id ASC);
    // title ordering is (title case-insensitive, event id); createdAt
    // ordering is (createdAt, event id). The trailing id tie-break keeps
    // consecutive page slices stable when instants/titles collide (same-venue
    // multi-session evenings, backfilled batches), because the candidate load
    // above has no DB ORDER BY and Java sort alone would leave ties
    // unspecified.
    private List<Event> applyCatalogOrdering(List<Event> events, Map<UUID, Instant> nextByEvent, Pageable pageable) {
        boolean sortByTitle = false;
        boolean sortByCreatedAt = false;
        boolean descending = false;
        for (org.springframework.data.domain.Sort.Order order : pageable.getSort()) {
            String property = order.getProperty();
            if ("title".equals(property)) {
                sortByTitle = true;
                descending = order.getDirection().isDescending();
                break;
            } else if ("createdAt".equals(property)) {
                sortByCreatedAt = true;
                descending = order.getDirection().isDescending();
                break;
            }
            // "nextSessionStartsAt" (or legacy default) falls through to session ordering.
        }
        List<Event> ordered = new ArrayList<>(events);
        if (sortByTitle) {
            java.util.Comparator<Event> comparator =
                    java.util.Comparator.comparing(Event::getTitle, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(Event::getId);
            ordered.sort(descending ? comparator.reversed() : comparator);
        } else if (sortByCreatedAt) {
            java.util.Comparator<Event> comparator = java.util.Comparator.comparing(Event::getCreatedAt)
                    .thenComparing(Event::getId);
            ordered.sort(descending ? comparator.reversed() : comparator);
        } else {
            boolean desc = pageable.getSort().stream()
                    .anyMatch(o -> "nextSessionStartsAt".equals(o.getProperty())
                            && o.getDirection().isDescending());
            java.util.Comparator<Event> comparator =
                    java.util.Comparator.<Event, Instant>comparing(e -> nextByEvent.get(e.getId()))
                            .thenComparing(Event::getId);
            ordered.sort(desc ? comparator.reversed() : comparator);
        }
        return ordered;
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResult<EventDetailResponse> findEventsForAdministration(EventStatus status, EventCategory category, String search, Pageable pageable) {
        Specification<Event> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("title")), pattern),
                    cb.like(cb.lower(root.get("description")), pattern)
                ));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<Event> page = eventRepository.findAll(spec, pageable);
        List<EventDetailResponse> content = page.getContent().stream()
                .map(event -> withSessions(eventMapper.toDetailResponse(event), event.getId(), true))
                .toList();
        return PagedResult.of(content, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public EventDetailResponse getPublishedEvent(UUID eventId) {
        Event event = eventRepository.findWithPricingTiersById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));
        if (event.getStatus() != EventStatus.PUBLISHED || !hasVisibleFutureSession(eventId)) {
            throw new ResourceNotFoundException("Event", eventId);
        }
        return withSessions(eventMapper.toDetailResponse(event), event.getId(), false);
    }

    @Override
    @Transactional(readOnly = true)
    public EventDetailResponse getEventForAdministration(UUID eventId) {
        Event event = eventRepository.findWithPricingTiersById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));
        return withSessions(eventMapper.toDetailResponse(event), event.getId(), true);
    }

    @Override
    @Transactional(readOnly = true)
    public EventSeatMapResponse getEventSeatMap(UUID eventId) {
        Event event = eventRepository.findWithPricingTiersById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));
        if (event.getStatus() != EventStatus.PUBLISHED || !hasVisibleFutureSession(eventId)) {
            throw new ResourceNotFoundException("Event", eventId);
        }
        SeatMapVenueLayout venue = seatMapClient.getVenueLayout(event.getVenueId());
        List<SeatMapVenueSection> sections = venue.sections() == null ? List.of() : venue.sections();

        List<PricingTierResponse> allTiers = event.getPricingTiers().stream()
                .map(tierMapper::toResponse).toList();
        List<SeatMapSectionResponse> mapped = sections.stream().map(vs -> {
            List<PricingTierResponse> sectionTiers = allTiers.stream()
                    .filter(t -> t.sectionId().equals(vs.sectionId())).toList();
            List<SeatMapSeatResponse> seats = vs.seats() == null ? List.of()
                    : vs.seats().stream().map(this::toSeatMapSeat).toList();
            return new SeatMapSectionResponse(vs.sectionId(), vs.name(), vs.rowCount(), vs.colCount(),
                    vs.isActive(), vs.positionX(), vs.positionY(), vs.width(), vs.height(),
                    vs.rotationDeg(), vs.zIndex(), vs.shapeMetadata(), seats, sectionTiers);
        }).toList();
        long totalConfiguredSeats = venue.totalConfiguredSeats() != null ? venue.totalConfiguredSeats() : 0L;
        long layoutVersion = venue.layoutVersion() != null ? venue.layoutVersion() : 0L;
        List<EventSeatMapResponse.LayoutElement> layoutElements = venue.elements() == null ? List.of()
                : venue.elements().stream().map(this::toLayoutElement).toList();
        log.info("Composed event seat map. eventId={}, venueId={}, layoutVersion={}, sections={}, elements={}, totalConfiguredSeats={}",
                event.getId(), event.getVenueId(), layoutVersion, mapped.size(), layoutElements.size(),
                totalConfiguredSeats);
        return new EventSeatMapResponse(event.getId(), event.getVenueId(), event.getTitle(), event.getStatus().name(),
                venue.name(), venue.capacity(), totalConfiguredSeats, mapped,
                layoutVersion, layoutElements);
    }

    private SeatMapSeatResponse toSeatMapSeat(SeatMapVenueSeat vs) {
        return new SeatMapSeatResponse(vs.seatId(), vs.rowLabel(), vs.seatNumber(), vs.gridX(), vs.gridY(),
                vs.isActive(), vs.positionX(), vs.positionY());
    }

    private EventSeatMapResponse.LayoutElement toLayoutElement(SeatMapVenueLayout.LayoutElement element) {
        EventSeatMapResponse.Geometry geometry = element.geometry() == null ? null
                : new EventSeatMapResponse.Geometry(
                        element.geometry().x(),
                        element.geometry().y(),
                        element.geometry().width(),
                        element.geometry().height(),
                        element.geometry().rotationDeg());
        return new EventSeatMapResponse.LayoutElement(
                element.elementId(), element.type(), element.label(), geometry, element.zIndex());
    }

    @Override
    @Transactional
    public int completeExpiredEvents(Instant now, int batchSize) {
        List<Event> completable = eventRepository.findPublishedCompletableForUpdate(now, PageRequest.of(0, batchSize));
        for (Event event : completable) {
            List<EventSession> ended = eventSessionRepository.findByEvent_IdAndStatusAndEndsAtLessThanEqual(
                    event.getId(), EventSessionStatus.SCHEDULED, now);
            for (EventSession session : ended) {
                session.setStatus(EventSessionStatus.COMPLETED);
            }
            event.setStatus(EventStatus.COMPLETED);
            event.setUpdatedAt(now);
            publishOutbox(EVENT_COMPLETED, event.getId(),
                    new EventCompletedEvent(event.getId(), event.getVenueId(), event.getTitle(),
                            now));
            log.info("Auto-completed event after all sessions ended. eventId={}, sessionsCompleted={}",
                    event.getId(), ended.size());
        }
        return completable.size();
    }

    private EventDetailResponse withSessions(EventDetailResponse base, UUID eventId, boolean adminView) {
        List<EventSessionResponse> sessions = adminView ? allSessionResponses(eventId) : visibleSessionResponses(eventId);
        return new EventDetailResponse(base.id(), base.venueId(), base.title(), base.description(),
                base.category(), base.bannerUrl(), base.status(),
                base.pricingTiers(), sessions, base.createdAt(), base.updatedAt());
    }

    private boolean hasVisibleFutureSession(UUID eventId) {
        // P12-007: visibility is derived from sessions only; never from a legacy
        // event-level instant and never by inferring "the" session for booking.
        Instant now = Instant.now();
        return eventSessionRepository
                .findByEvent_IdAndStatusAndEndsAtAfterOrderByStartsAtAscIdAsc(
                        eventId, EventSessionStatus.SCHEDULED, now).stream()
                .anyMatch(session -> session.getStartsAt().isAfter(now));
    }

    private List<EventSessionResponse> allSessionResponses(UUID eventId) {
        return eventSessionRepository.findByEvent_IdOrderByStartsAtAscIdAsc(eventId).stream()
                .map(eventSessionMapper::toResponse)
                .toList();
    }

    private List<EventSessionResponse> visibleSessionResponses(UUID eventId) {
        Instant now = Instant.now();
        return eventSessionRepository
                .findByEvent_IdAndStatusAndEndsAtAfterOrderByStartsAtAscIdAsc(
                        eventId, EventSessionStatus.SCHEDULED, now).stream()
                .filter(session -> session.getStartsAt().isAfter(now))
                .map(eventSessionMapper::toResponse)
                .toList();
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
        EventEnvelope<? extends DomainEvent> base = EventEnvelope.of(eventType, aggregateId.toString(), correlationId, domainEvent);
        Map<String, String> headers = new java.util.HashMap<>();
        try {
            if (w3cTraceContextPropagator != null) {
                w3cTraceContextPropagator.inject(headers);
            }
        } catch (Exception ignored) {
            // best-effort
        }
        EventEnvelope<? extends DomainEvent> envelope = base.withHeaders(headers);
        Map<String, Object> payload = objectMapper.convertValue(envelope, new TypeReference<Map<String, Object>>() {});
        outboxEventRepository.save(OutboxEvent.builder()
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payload)
                .build());
    }
}
