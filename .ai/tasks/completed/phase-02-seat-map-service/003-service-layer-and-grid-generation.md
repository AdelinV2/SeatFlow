# TASK-P02-003: Service Layer, Grid Seat Generation & Domain Event Logic

## 1. Task Metadata
- **Task ID:** `TASK-P02-003`
- **Git Branch:** `feat/p02-003-service-layer-and-grid-generation`
- **Target Module:** `backend/services/seat-map-service`
- **Phase:** `Phase 02 - Seat Map & Venue Service`
- **Related Specs:** `.ai/architecture/02-microservices-spec.md` (Section 4), `.ai/architecture/03-database-models.md` (Section 2.2), `.ai/architecture/05-messaging-and-outbox.md` (Section 3), `.ai/architecture/06-api-contracts.md` (Section 2.2)
- **Related ADRs:** `.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md`
- **Dependencies:** `TASK-P02-002` (Entities, repositories, DTOs, and mappers must exist)
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the service layer interfaces and implementations for `VenueService`, `VenueSectionService`, and `SeatMapLayoutService`. This includes automated grid seat generation logic (generating a `rowCount × colCount` seat matrix with alphabetic row labels), venue capacity validation, active seat toggling, and domain event generation (`VenueCreatedEvent`, `VenueSectionCreatedEvent`) saved to the outbox within the same transaction.

### Critical Invariants to Enforce:
- [ ] No direct Kafka publishing — all domain events committed to `outbox_events` table (Transactional Outbox Pattern).
- [ ] Outbox payload is wrapped in `EventEnvelope<T>` from `common-events` with correlation context propagated from `CorrelationContext`.
- [ ] Seat grid generation produces `rowCount × colCount` seats with:
  - Row labels: A, B, C, ..., Z, AA, AB, ... (alphabetic progression for rows).
  - Seat numbers: 1 through `colCount` per row.
  - Grid coordinates: `grid_x` = column index (0-based), `grid_y` = row index (0-based).
- [ ] Duplicate venue names within the same city are rejected with `ConflictException`.
- [ ] Duplicate section names within the same venue are rejected with `ConflictException`.
- [ ] All service methods use `@Transactional` with `readOnly = true` for query methods.
- [ ] Structured contextual logging with domain identifiers on all business events.
- [ ] Admin pagination uses `PagedResult<T>` from `common-domain`.
- [ ] Seat toggle (`isActive`) only modifies the active status — never deletes the seat.

---

## 3. Exact File Inventory

All paths relative to `backend/services/seat-map-service/`.

- `[NEW]` `src/main/java/com/seatflow/seatmap/service/VenueService.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/service/impl/VenueServiceImpl.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/service/VenueSectionService.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/service/impl/VenueSectionServiceImpl.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/service/SeatMapLayoutService.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/service/impl/SeatMapLayoutServiceImpl.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Service Interface: `VenueService`
```java
package com.seatflow.seatmap.service;

import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.seatmap.web.dto.request.CreateVenueRequest;
import com.seatflow.seatmap.web.dto.request.UpdateVenueRequest;
import com.seatflow.seatmap.web.dto.response.VenueDetailResponse;
import com.seatflow.seatmap.web.dto.response.VenueResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface VenueService {

    /**
     * Create a new venue. Writes a VenueCreatedEvent to outbox_events in the same transaction.
     * Rejects duplicate (name, city) combinations with ConflictException.
     */
    VenueResponse createVenue(CreateVenueRequest request);

    /**
     * Update an existing venue's mutable fields (name, address, city, country, capacity).
     * Only updates non-null fields from the request.
     */
    VenueResponse updateVenue(UUID venueId, UpdateVenueRequest request);

    /**
     * Retrieve a single venue by ID with its sections.
     * Throws ResourceNotFoundException if venue does not exist.
     */
    VenueDetailResponse getVenueById(UUID venueId);

    /**
     * List all venues with pagination and optional filtering by city and name search.
     */
    PagedResult<VenueResponse> listVenues(String city, String name, Pageable pageable);
}
```

### 4.2 Service Interface: `VenueSectionService`
```java
package com.seatflow.seatmap.service;

import com.seatflow.seatmap.web.dto.request.CreateVenueSectionRequest;
import com.seatflow.seatmap.web.dto.request.UpdateSeatStatusRequest;
import com.seatflow.seatmap.web.dto.response.SeatResponse;
import com.seatflow.seatmap.web.dto.response.VenueSectionResponse;

import java.util.UUID;

public interface VenueSectionService {

    /**
     * Create a new section in a venue and auto-generate the seat grid (rowCount × colCount).
     * Row labels are generated alphabetically: A, B, ..., Z, AA, AB, ...
     * Grid coordinates: grid_x = column index (0-based), grid_y = row index (0-based).
     * Writes a VenueSectionCreatedEvent to outbox_events in the same transaction.
     * Rejects duplicate section names within the same venue with ConflictException.
     */
    VenueSectionResponse createSection(UUID venueId, CreateVenueSectionRequest request);

    /**
     * Toggle a seat's active/inactive status within a section.
     * Throws ResourceNotFoundException if seat or section does not exist.
     */
    SeatResponse updateSeatStatus(UUID venueId, UUID sectionId, UUID seatId, UpdateSeatStatusRequest request);
}
```

### 4.3 Service Interface: `SeatMapLayoutService`
```java
package com.seatflow.seatmap.service;

import com.seatflow.seatmap.web.dto.response.VenueSeatMapLayoutResponse;

import java.util.UUID;

public interface SeatMapLayoutService {

    /**
     * Retrieve the complete venue seat map layout with all sections and their active seats.
     * Used by the interactive seat map UI and downstream event-service.
     * Throws ResourceNotFoundException if venue does not exist.
     */
    VenueSeatMapLayoutResponse getVenueLayout(UUID venueId);
}
```

### 4.4 Service Implementation: `VenueServiceImpl`
```java
package com.seatflow.seatmap.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
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
                .build();

        venue = venueRepository.save(venue);
        log.info("Venue created. venueId={}, name={}, city={}, capacity={}",
                venue.getId(), venue.getName(), venue.getCity(), venue.getCapacity());

        // 3. Write VenueCreatedEvent to outbox
        writeOutboxEvent(venue.getId(), "VenueCreated", new VenueCreatedEvent(
                venue.getId(), venue.getName(), venue.getCity(),
                venue.getCapacity(), venue.getCreatedAt()
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

        if (request.name() != null) venue.setName(request.name());
        if (request.address() != null) venue.setAddress(request.address());
        if (request.city() != null) venue.setCity(request.city());
        if (request.country() != null) venue.setCountry(request.country());
        if (request.capacity() != null) venue.setCapacity(request.capacity());

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

        return venueMapper.toDetailResponse(venue, sectionResponses);
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
        log.info("Outbox event written. aggregateId={}, eventType={}, outboxEventId={}",
                aggregateId, eventType, outboxEvent.getId());
    }
}
```

### 4.5 Service Implementation: `VenueSectionServiceImpl`
```java
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
        writeOutboxEvent(section.getId(), "VenueSectionCreated", new VenueSectionCreatedEvent(
                section.getId(), venueId, section.getName(),
                section.getRowCount(), section.getColCount(),
                totalSeats, section.getCreatedAt()
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
    static String generateRowLabel(int rowIndex) {
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
        log.info("Outbox event written. aggregateId={}, eventType={}, outboxEventId={}",
                aggregateId, eventType, outboxEvent.getId());
    }
}
```

### 4.6 Service Implementation: `SeatMapLayoutServiceImpl`
```java
package com.seatflow.seatmap.service.impl;

import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.seatmap.mapper.SeatMapper;
import com.seatflow.seatmap.model.entity.Venue;
import com.seatflow.seatmap.model.entity.VenueSection;
import com.seatflow.seatmap.repository.SeatRepository;
import com.seatflow.seatmap.repository.VenueRepository;
import com.seatflow.seatmap.repository.VenueSectionRepository;
import com.seatflow.seatmap.service.SeatMapLayoutService;
import com.seatflow.seatmap.web.dto.response.SeatResponse;
import com.seatflow.seatmap.web.dto.response.SectionLayoutResponse;
import com.seatflow.seatmap.web.dto.response.VenueSeatMapLayoutResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeatMapLayoutServiceImpl implements SeatMapLayoutService {

    private final VenueRepository venueRepository;
    private final VenueSectionRepository sectionRepository;
    private final SeatRepository seatRepository;
    private final SeatMapper seatMapper;

    @Override
    @Transactional(readOnly = true)
    public VenueSeatMapLayoutResponse getVenueLayout(UUID venueId) {
        Venue venue = venueRepository.findById(venueId)
                .orElseThrow(() -> new ResourceNotFoundException("Venue not found: " + venueId));

        List<VenueSection> sections = sectionRepository.findByVenueIdOrderByNameAsc(venueId);

        List<SectionLayoutResponse> sectionLayouts = sections.stream()
                .map(section -> {
                    List<SeatResponse> seatResponses = seatRepository
                            .findActiveSeatsBySectionId(section.getId())
                            .stream()
                            .map(seatMapper::toResponse)
                            .toList();

                    return new SectionLayoutResponse(
                            section.getId(), section.getName(),
                            section.getRowCount(), section.getColCount(),
                            seatResponses
                    );
                })
                .toList();

        log.debug("Venue layout retrieved. venueId={}, name={}, sectionCount={}",
                venueId, venue.getName(), sectionLayouts.size());

        return new VenueSeatMapLayoutResponse(
                venue.getId(), venue.getName(),
                venue.getCapacity(), sectionLayouts
        );
    }
}
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Step 1 — Branch Checkout:** `git checkout -b feat/p02-003-service-layer-and-grid-generation develop`
2. **Step 2 — VenueService Interface:** Create `VenueService.java` with `createVenue`, `updateVenue`, `getVenueById`, `listVenues`.
3. **Step 3 — VenueServiceImpl:** Create implementation with duplicate check, outbox event writing, pagination.
4. **Step 4 — VenueSectionService Interface:** Create `VenueSectionService.java` with `createSection`, `updateSeatStatus`.
5. **Step 5 — VenueSectionServiceImpl:** Create implementation with seat grid generation, row label algorithm, outbox writing.
6. **Step 6 — SeatMapLayoutService Interface:** Create `SeatMapLayoutService.java` with `getVenueLayout`.
7. **Step 7 — SeatMapLayoutServiceImpl:** Create implementation assembling venue → sections → active seats response.
8. **Step 8 — Verify Compilation:** Run `mvn clean compile -pl services/seat-map-service -am` from `backend/`.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the `backend/` directory:
```bash
mvn clean compile -pl services/seat-map-service -am
```
- [ ] All service interfaces and implementations compile cleanly.
- [ ] Seat grid generation produces correct `rowCount × colCount` seats with alphabetic row labels.
- [ ] `VenueCreatedEvent` and `VenueSectionCreatedEvent` are wrapped in `EventEnvelope<T>` and saved to outbox.
- [ ] Duplicate venue/section checks use repository existence queries with proper `ConflictException`.
- [ ] `PagedResult.of(...)` from `common-domain` used for venue pagination.
- [ ] Structured logging with domain identifiers on all business events.
- [ ] No direct Kafka publishing — all events go to outbox table.
- [ ] Task file is moved to `.ai/tasks/completed/phase-02-seat-map-service/003-service-layer-and-grid-generation.md`.
