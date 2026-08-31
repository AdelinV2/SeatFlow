package com.seatflow.seatmap.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.events.DomainEvent;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.seatmap.mapper.VenueMapper;
import com.seatflow.seatmap.mapper.VenueSectionMapper;
import com.seatflow.seatmap.messaging.event.VenueCreatedEvent;
import com.seatflow.seatmap.model.entity.OutboxEvent;
import com.seatflow.seatmap.model.entity.Venue;
import com.seatflow.seatmap.repository.OutboxEventRepository;
import com.seatflow.seatmap.repository.SeatRepository;
import com.seatflow.seatmap.repository.VenueRepository;
import com.seatflow.seatmap.repository.VenueSectionRepository;
import com.seatflow.seatmap.service.VenueService;
import com.seatflow.seatmap.web.dto.request.CreateVenueRequest;
import com.seatflow.seatmap.web.dto.request.UpdateVenueRequest;
import com.seatflow.seatmap.web.dto.response.VenueDetailResponse;
import com.seatflow.seatmap.web.dto.response.VenueResponse;
import com.seatflow.seatmap.web.dto.response.VenueSectionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class VenueServiceImpl implements VenueService {

    private final VenueRepository venueRepository;
    private final VenueSectionRepository sectionRepository;
    private final SeatRepository seatRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final VenueMapper venueMapper;
    private final VenueSectionMapper venueSectionMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public VenueResponse createVenue(CreateVenueRequest request) {
        // 1. Check for duplicate (name, city)
        if (venueRepository.existsByNameAndCity(request.name(), request.city())) {
            throw new ConflictException(
                "Venue '%s' already exists in city '%s'".formatted(request.name(), request.city())
            );
        }

        // 2. Build and persist venue
        Venue venue = Venue.builder()
                .name(request.name())
                .address(request.address())
                .city(request.city())
                .country(request.country() != null ? request.country() : "USA")
                .capacity(request.capacity())
                .latitude(request.latitude())
                .longitude(request.longitude())
                .build();

        venue = venueRepository.save(venue);
        log.info("Venue created. venueId={}, name={}, city={}, capacity={}",
                venue.getId(), venue.getName(), venue.getCity(), venue.getCapacity());

        // 3. Write VenueCreatedEvent to outbox
        Instant createdAt = venue.getCreatedAt() != null ? venue.getCreatedAt() : Instant.now();
        writeOutboxEvent(venue.getId(), "VenueCreated", new VenueCreatedEvent(
                venue.getId(), venue.getName(), venue.getCity(),
                venue.getCapacity(), createdAt
        ));

        return venueMapper.toResponse(venue);
    }

    @Override
    @Transactional
    public VenueResponse updateVenue(UUID venueId, UpdateVenueRequest request) {
        Venue venue = venueRepository.findById(venueId)
                .orElseThrow(() -> new ResourceNotFoundException("Venue not found: " + venueId));

        String targetName = request.name() != null ? request.name() : venue.getName();
        String targetCity = request.city() != null ? request.city() : venue.getCity();

        // Check for duplicate (name, city) if name or city is changing
        if ((!targetName.equalsIgnoreCase(venue.getName()) || !targetCity.equalsIgnoreCase(venue.getCity()))
                && venueRepository.existsByNameAndCity(targetName, targetCity)) {
            throw new ConflictException(
                "Venue '%s' already exists in city '%s'".formatted(targetName, targetCity)
            );
        }

        // Validate capacity reduction against currently configured active seats
        if (request.capacity() != null) {
            long currentActiveSeats = seatRepository.countActiveSeatsByVenueId(venueId);
            if (request.capacity() < currentActiveSeats) {
                throw new ValidationException(
                    "Cannot reduce venue capacity to %d because %d active seats are already configured across sections"
                            .formatted(request.capacity(), currentActiveSeats),
                    ErrorCode.INVALID_REQUEST
                );
            }
            venue.setCapacity(request.capacity());
        }

        if (request.name() != null) venue.setName(request.name());
        if (request.address() != null) venue.setAddress(request.address());
        if (request.city() != null) venue.setCity(request.city());
        if (request.country() != null) venue.setCountry(request.country());
        if (request.latitude() != null) venue.setLatitude(request.latitude());
        if (request.longitude() != null) venue.setLongitude(request.longitude());

        venue = venueRepository.save(venue);
        log.info("Venue updated. venueId={}, name={}", venue.getId(), venue.getName());
        return venueMapper.toResponse(venue);
    }

    @Override
    @Transactional(readOnly = true)
    public VenueDetailResponse getVenueById(UUID venueId) {
        Venue venue = venueRepository.findById(venueId)
                .orElseThrow(() -> new ResourceNotFoundException("Venue not found: " + venueId));

        List<VenueSectionResponse> sectionResponses = sectionRepository.findByVenueIdOrderByNameAsc(venueId)
                .stream()
                .map(section -> venueSectionMapper.toResponse(
                        section,
                        seatRepository.countBySectionIdAndIsActiveTrue(section.getId())
                ))
                .toList();

        long totalConfiguredSeats = sectionResponses.stream()
                .mapToLong(VenueSectionResponse::activeSeatCount)
                .sum();

        return venueMapper.toDetailResponse(venue, totalConfiguredSeats, sectionResponses);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResult<VenueResponse> listVenues(String city, String name, Pageable pageable) {
        Page<Venue> page = venueRepository.findByFilters(city, name, pageable);
        var content = page.getContent().stream()
                .map(venueMapper::toResponse)
                .toList();
        return PagedResult.of(content, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    // ---- Private Helpers ----

    private <T extends DomainEvent> void writeOutboxEvent(UUID aggregateId, String eventType, T eventPayload) {
        String correlationId = CorrelationContext.getCorrelationId().orElse(UUID.randomUUID().toString());
        EventEnvelope<T> envelope = EventEnvelope.of(eventType, aggregateId.toString(), correlationId, eventPayload);

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize EventEnvelope. aggregateId={}, eventType={}", aggregateId, eventType, e);
            throw new BusinessException("Failed to serialize domain event envelope", e, ErrorCode.INTERNAL_SERVER_ERROR, 500);
        }

        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payloadJson)
                .build();
        outboxEvent = outboxEventRepository.save(outboxEvent);
        log.debug("Outbox event written. aggregateId={}, eventType={}, outboxId={}",
                aggregateId, eventType, outboxEvent.getId());
    }
}
