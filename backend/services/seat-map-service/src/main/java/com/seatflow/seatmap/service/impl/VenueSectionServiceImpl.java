package com.seatflow.seatmap.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.events.DomainEvent;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.seatmap.mapper.SeatMapper;
import com.seatflow.seatmap.mapper.VenueSectionMapper;
import com.seatflow.seatmap.messaging.event.VenueSectionCreatedEvent;
import com.seatflow.seatmap.model.entity.OutboxEvent;
import com.seatflow.seatmap.model.entity.Seat;
import com.seatflow.seatmap.model.entity.Venue;
import com.seatflow.seatmap.model.entity.VenueSection;
import com.seatflow.seatmap.repository.OutboxEventRepository;
import com.seatflow.seatmap.repository.SeatRepository;
import com.seatflow.seatmap.repository.VenueRepository;
import com.seatflow.seatmap.repository.VenueSectionRepository;
import com.seatflow.seatmap.service.VenueSectionService;
import com.seatflow.seatmap.web.dto.request.CreateVenueSectionRequest;
import com.seatflow.seatmap.web.dto.request.UpdateSeatStatusRequest;
import com.seatflow.seatmap.web.dto.response.SeatResponse;
import com.seatflow.seatmap.web.dto.response.VenueSectionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class VenueSectionServiceImpl implements VenueSectionService {

    private final VenueRepository venueRepository;
    private final VenueSectionRepository sectionRepository;
    private final SeatRepository seatRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final SeatMapper seatMapper;
    private final VenueSectionMapper venueSectionMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public VenueSectionResponse createSection(UUID venueId, CreateVenueSectionRequest request) {
        // 1. Validate venue exists
        Venue venue = venueRepository.findById(venueId)
                .orElseThrow(() -> new ResourceNotFoundException("Venue not found: " + venueId));

        // 2. Check for duplicate section name
        if (sectionRepository.existsByVenueIdAndName(venueId, request.name())) {
            throw new ConflictException(
                "Section '%s' already exists in venue '%s'".formatted(request.name(), venue.getName())
            );
        }

        // 3. Validate venue capacity is not exceeded
        int newSeats = request.rowCount() * request.colCount();
        long currentSeats = seatRepository.countActiveSeatsByVenueId(venueId);
        if (currentSeats + newSeats > venue.getCapacity()) {
            throw new ValidationException(
                "Creating section would exceed venue capacity of %d (current: %d, requested: %d)"
                        .formatted(venue.getCapacity(), currentSeats, newSeats),
                ErrorCode.INVALID_REQUEST
            );
        }

        // 4. Create section
        VenueSection section = VenueSection.builder()
                .venue(venue)
                .name(request.name())
                .rowCount(request.rowCount())
                .colCount(request.colCount())
                .build();
        section = sectionRepository.save(section);

        // 5. Auto-generate seat grid: rowCount × colCount
        List<Seat> seats = generateSeatGrid(section, request.rowCount(), request.colCount());
        seatRepository.saveAll(seats);
        int totalSeats = seats.size();

        log.info("Section created with seat grid. venueId={}, sectionId={}, name={}, rows={}, cols={}, totalSeats={}",
                venueId, section.getId(), section.getName(), request.rowCount(), request.colCount(), totalSeats);

        // 6. Write outbox event
        Instant createdAt = section.getCreatedAt() != null ? section.getCreatedAt() : Instant.now();
        writeOutboxEvent(section.getId(), "VenueSectionCreated", new VenueSectionCreatedEvent(
                section.getId(), venueId, section.getName(),
                section.getRowCount(), section.getColCount(),
                totalSeats, createdAt
        ));

        return venueSectionMapper.toResponse(section, (long) totalSeats);
    }

    @Override
    @Transactional
    public SeatResponse updateSeatStatus(UUID venueId, UUID sectionId, UUID seatId, UpdateSeatStatusRequest request) {
        // 1. Validate venue exists
        if (!venueRepository.existsById(venueId)) {
            throw new ResourceNotFoundException("Venue not found: " + venueId);
        }

        // 2. Validate section exists and belongs to venue
        VenueSection section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Section not found: " + sectionId));
        if (!section.getVenue().getId().equals(venueId)) {
            throw new ResourceNotFoundException("Section %s does not belong to venue %s".formatted(sectionId, venueId));
        }

        // 3. Find the seat within the section
        Seat seat = seatRepository.findByIdAndSectionId(seatId, sectionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Seat not found: seatId=%s, sectionId=%s".formatted(seatId, sectionId)));

        seat.setIsActive(request.isActive());
        seat = seatRepository.save(seat);

        log.info("Seat status updated. venueId={}, sectionId={}, seatId={}, isActive={}",
                venueId, sectionId, seatId, seat.getIsActive());

        return seatMapper.toResponse(seat);
    }

    @Override
    @Transactional
    public void deleteSection(UUID venueId, UUID sectionId) {
        // 1. Validate venue exists
        if (!venueRepository.existsById(venueId)) {
            throw new ResourceNotFoundException("Venue not found: " + venueId);
        }

        // 2. Validate section exists and belongs to venue
        VenueSection section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Section not found: " + sectionId));
        if (!section.getVenue().getId().equals(venueId)) {
            throw new ResourceNotFoundException("Section %s does not belong to venue %s".formatted(sectionId, venueId));
        }

        // 3. Delete section (cascades to seats)
        sectionRepository.delete(section);
        log.info("Section deleted. venueId={}, sectionId={}, name={}", venueId, sectionId, section.getName());
    }

    // ---- Private Helpers ----

    /**
     * Generates a grid of seats for a section.
     * Row labels: A, B, C, ..., Z, AA, AB, ...
     * Seat numbers: 1 through colCount per row.
     * Grid coordinates: grid_x = column index (0-based), grid_y = row index (0-based).
     */
    private List<Seat> generateSeatGrid(VenueSection section, int rowCount, int colCount) {
        List<Seat> seats = new ArrayList<>(rowCount * colCount);

        for (int row = 0; row < rowCount; row++) {
            String rowLabel = generateRowLabel(row);

            for (int col = 0; col < colCount; col++) {
                Seat seat = Seat.builder()
                        .section(section)
                        .rowLabel(rowLabel)
                        .seatNumber(col + 1)
                        .gridX(col)
                        .gridY(row)
                        .isActive(true)
                        .build();
                seats.add(seat);
            }
        }

        return seats;
    }

    /**
     * Generates alphabetic row labels: 0 → "A", 25 → "Z", 26 → "AA", 27 → "AB", ...
     */
    public static String generateRowLabel(int rowIndex) {
        StringBuilder label = new StringBuilder();
        int index = rowIndex;
        do {
            label.insert(0, (char) ('A' + (index % 26)));
            index = index / 26 - 1;
        } while (index >= 0);
        return label.toString();
    }

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
