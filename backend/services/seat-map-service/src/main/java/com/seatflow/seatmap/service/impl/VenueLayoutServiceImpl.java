package com.seatflow.seatmap.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.events.DomainEvent;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.common.observability.tracing.W3cTraceContextPropagator;
import com.seatflow.seatmap.mapper.SeatMapper;
import com.seatflow.seatmap.messaging.event.VenueSectionCreatedEvent;
import com.seatflow.seatmap.model.entity.OutboxEvent;
import com.seatflow.seatmap.model.entity.Seat;
import com.seatflow.seatmap.model.entity.Venue;
import com.seatflow.seatmap.model.entity.VenueLayoutElement;
import com.seatflow.seatmap.model.entity.VenueSection;
import com.seatflow.seatmap.repository.SeatRepository;
import com.seatflow.seatmap.repository.OutboxEventRepository;
import com.seatflow.seatmap.repository.VenueLayoutElementRepository;
import com.seatflow.seatmap.repository.VenueRepository;
import com.seatflow.seatmap.repository.VenueSectionRepository;
import com.seatflow.seatmap.service.LayoutValidationService;
import com.seatflow.seatmap.service.VenueLayoutService;
import com.seatflow.seatmap.web.dto.request.SaveVenueLayoutRequest;
import com.seatflow.seatmap.web.dto.response.LayoutElementResponse;
import com.seatflow.seatmap.web.dto.response.SeatResponse;
import com.seatflow.seatmap.web.dto.response.SectionLayoutResponse;
import com.seatflow.seatmap.web.dto.response.VenueSeatMapLayoutResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class VenueLayoutServiceImpl implements VenueLayoutService {

    private final VenueRepository venueRepository;
    private final VenueSectionRepository sectionRepository;
    private final SeatRepository seatRepository;
    private final VenueLayoutElementRepository elementRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final LayoutValidationService validationService;
    private final SeatMapper seatMapper;
    private final ObjectMapper objectMapper;
    private final W3cTraceContextPropagator w3cTraceContextPropagator;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VenueSeatMapLayoutResponse getEditableLayout(UUID venueId) {
        Venue venue = venueRepository.findById(venueId)
                .orElseThrow(() -> new ResourceNotFoundException("Venue not found: " + venueId));
        return buildEditorResponse(venue);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void validateLayout(UUID venueId, SaveVenueLayoutRequest request) {
        request = canonicalize(request);
        Venue venue = venueRepository.findById(venueId)
                .orElseThrow(() -> new ResourceNotFoundException("Venue not found: " + venueId));
        LayoutValidationService.ExistingLayoutIds ids = loadExistingIds(venueId);
        validationService.validate(venue, request, ids);
    }

    @Override
    @Transactional
    public VenueSeatMapLayoutResponse saveLayout(UUID venueId, SaveVenueLayoutRequest request) {
        long startNanos = System.nanoTime();
        request = canonicalize(request);

        // 1. Lock the venue row as the single transaction root.
        Venue venue = venueRepository.findByIdForLayoutUpdate(venueId)
                .orElseThrow(() -> new ResourceNotFoundException("Venue not found: " + venueId));

        // 2-3. Compare versions before any mutation.
        Long currentVersion = venue.getLayoutVersion();
        if (!Objects.equals(currentVersion, request.layoutVersion())) {
            log.warn("Stale layout save rejected. venueId={}, expectedVersion={}, currentVersion={}",
                    venueId, request.layoutVersion(), currentVersion);
            throw new ConflictException(
                    "Layout version mismatch: expected %s but current is %s"
                            .formatted(request.layoutVersion(), currentVersion),
                    ErrorCode.CONFLICT);
        }

        // 4. Load existing IDs and run TASK-P11-003 validation before mutation.
        List<VenueSection> existingSections =
                sectionRepository.findByVenueIdOrderByZIndexAscNameAsc(venueId);
        Map<UUID, VenueSection> sectionById = existingSections.stream()
                .filter(s -> s.getId() != null)
                .collect(Collectors.toMap(VenueSection::getId, Function.identity(), (a, b) -> a));

        Map<UUID, List<Seat>> seatsBySectionId = new HashMap<>();
        Map<UUID, Seat> seatById = new HashMap<>();
        Map<UUID, UUID> seatToSection = new HashMap<>();
        Map<UUID, SectionIdentity> originalSectionIdentities = new HashMap<>();
        Map<UUID, SeatIdentity> originalSeatIdentities = new HashMap<>();
        for (VenueSection section : existingSections) {
            originalSectionIdentities.put(section.getId(), new SectionIdentity(section.getName()));
            List<Seat> seats = seatRepository.findBySectionIdForEditor(section.getId());
            seatsBySectionId.put(section.getId(), seats);
            for (Seat seat : seats) {
                if (seat.getId() != null) {
                    seatById.put(seat.getId(), seat);
                    seatToSection.put(seat.getId(), section.getId());
                    originalSeatIdentities.put(seat.getId(), new SeatIdentity(
                            seat.getRowLabel(), seat.getSeatNumber(), seat.getGridX(), seat.getGridY()));
                }
            }
        }

        List<VenueLayoutElement> existingElements =
                elementRepository.findByVenueIdOrderByZIndexAscIdAsc(venueId);
        Map<UUID, VenueLayoutElement> elementById = existingElements.stream()
                .filter(e -> e.getId() != null)
                .collect(Collectors.toMap(VenueLayoutElement::getId, Function.identity(), (a, b) -> a));

        LayoutValidationService.ExistingLayoutIds ids =
                new LayoutValidationService.ExistingLayoutIds(
                        new HashSet<>(sectionById.keySet()), seatToSection,
                        new HashSet<>(elementById.keySet()));
        validationService.validate(venue, request, ids);

        // Free the immediate uniqueness keys before applying the requested final snapshot.
        // The staging flush stays inside this transaction, so any later failure restores
        // every original value and the stable section/seat UUIDs.
        stageExistingUniquenessKeys(existingSections, seatsBySectionId, request);

        // 5. Update matched sections/seats in place; create only records with null IDs.
        Set<UUID> requestedSectionIds = request.sections().stream()
                .map(SaveVenueLayoutRequest.SectionUpsert::sectionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<VenueSection> newSections = new ArrayList<>();
        List<CreatedSection> createdSectionEvents = new ArrayList<>();
        Map<UUID, VenueSection> targetSectionByRequestId = new HashMap<>();
        List<VenueSection> allTargetSections = new ArrayList<>();

        for (SaveVenueLayoutRequest.SectionUpsert upsert : request.sections()) {
            if (upsert.sectionId() == null) {
                VenueSection created = VenueSection.builder()
                        .venue(venue)
                        .name(upsert.name())
                        .rowCount(upsert.rowCount())
                        .colCount(upsert.colCount())
                        .isActive(upsert.isActive())
                        .positionX(upsert.positionX())
                        .positionY(upsert.positionY())
                        .width(upsert.width())
                        .height(upsert.height())
                        .rotationDeg(upsert.rotationDeg())
                        .zIndex(upsert.zIndex())
                        .shapeMetadata(upsert.shapeMetadata())
                        .build();
                created = sectionRepository.save(created);
                newSections.add(created);
                createdSectionEvents.add(new CreatedSection(created, upsert));
                allTargetSections.add(created);
                seatsBySectionId.put(created.getId(), new ArrayList<>());
            } else {
                VenueSection existing = sectionById.get(upsert.sectionId());
                existing.setName(upsert.name());
                existing.setRowCount(upsert.rowCount());
                existing.setColCount(upsert.colCount());
                existing.setIsActive(upsert.isActive());
                existing.setPositionX(upsert.positionX());
                existing.setPositionY(upsert.positionY());
                existing.setWidth(upsert.width());
                existing.setHeight(upsert.height());
                existing.setRotationDeg(upsert.rotationDeg());
                existing.setZIndex(upsert.zIndex());
                existing.setShapeMetadata(upsert.shapeMetadata());
                targetSectionByRequestId.put(upsert.sectionId(), existing);
                allTargetSections.add(existing);
            }
        }

        // Map newly created sections by identity for seat resolution below.
        // New sections are referenced positionally since their IDs were null in the request.
        List<VenueSection> createdInOrder = newSections;

        // Apply seat upserts per requested section, preserving iteration order.
        Map<UUID, List<Seat>> newSeatsBySectionId = new HashMap<>();
        int createdCursor = 0;
        for (SaveVenueLayoutRequest.SectionUpsert upsert : request.sections()) {
            VenueSection target;
            List<Seat> existingSeats;
            if (upsert.sectionId() == null) {
                target = createdInOrder.get(createdCursor++);
                existingSeats = new ArrayList<>();
            } else {
                target = targetSectionByRequestId.get(upsert.sectionId());
                existingSeats = seatsBySectionId.getOrDefault(upsert.sectionId(), List.of());
            }
            Map<UUID, Seat> existingSeatMap = existingSeats.stream()
                    .filter(s -> s.getId() != null)
                    .collect(Collectors.toMap(Seat::getId, Function.identity(), (a, b) -> a));
            Set<UUID> requestedSeatIds = upsert.seats().stream()
                    .map(SaveVenueLayoutRequest.SeatUpsert::seatId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            List<Seat> createdSeats = new ArrayList<>();
            for (SaveVenueLayoutRequest.SeatUpsert seatUpsert : upsert.seats()) {
                if (seatUpsert.seatId() == null) {
                    Seat created = Seat.builder()
                            .section(target)
                            .rowLabel(seatUpsert.rowLabel())
                            .seatNumber(seatUpsert.seatNumber())
                            .gridX(seatUpsert.gridX())
                            .gridY(seatUpsert.gridY())
                            .positionX(seatUpsert.positionX())
                            .positionY(seatUpsert.positionY())
                            .isActive(seatUpsert.isActive())
                            .build();
                    created = seatRepository.save(created);
                    createdSeats.add(created);
                } else {
                    Seat existingSeat = existingSeatMap.get(seatUpsert.seatId());
                    existingSeat.setRowLabel(seatUpsert.rowLabel());
                    existingSeat.setSeatNumber(seatUpsert.seatNumber());
                    existingSeat.setGridX(seatUpsert.gridX());
                    existingSeat.setGridY(seatUpsert.gridY());
                    existingSeat.setPositionX(seatUpsert.positionX());
                    existingSeat.setPositionY(seatUpsert.positionY());
                    existingSeat.setIsActive(seatUpsert.isActive());
                }
            }
            // 6b. Deactivate omitted seats in included sections; never delete.
            for (Seat existingSeat : existingSeats) {
                if (existingSeat.getId() != null && !requestedSeatIds.contains(existingSeat.getId())) {
                    restoreSeatIdentity(existingSeat, originalSeatIdentities.get(existingSeat.getId()));
                    existingSeat.setIsActive(false);
                }
            }
            if (target.getId() != null) {
                newSeatsBySectionId.computeIfAbsent(target.getId(), k -> new ArrayList<>()).addAll(createdSeats);
                // Keep the in-memory seat list complete for capacity computation.
                List<Seat> merged = new ArrayList<>(existingSeats);
                merged.addAll(createdSeats);
                seatsBySectionId.put(target.getId(), merged);
            }
        }

        // 6a. Deactivate omitted sections and all their seats; never delete.
        for (VenueSection existing : existingSections) {
            if (existing.getId() != null && !requestedSectionIds.contains(existing.getId())) {
                existing.setName(originalSectionIdentities.get(existing.getId()).name());
                existing.setIsActive(false);
                List<Seat> seats = seatsBySectionId.getOrDefault(existing.getId(), List.of());
                for (Seat seat : seats) {
                    restoreSeatIdentity(seat, originalSeatIdentities.get(seat.getId()));
                    seat.setIsActive(false);
                }
            }
        }

        for (CreatedSection createdSection : createdSectionEvents) {
            VenueSection section = createdSection.section();
            SaveVenueLayoutRequest.SectionUpsert upsert = createdSection.upsert();
            Instant createdAt = section.getCreatedAt() != null ? section.getCreatedAt() : Instant.now();
            writeOutboxEvent(section.getId(), "VenueSectionCreated", new VenueSectionCreatedEvent(
                    section.getId(), venueId, section.getName(), section.getRowCount(), section.getColCount(),
                    upsert.seats().size(), createdAt));
        }

        // 7. Replace omitted elements in-transaction; update matched, create null IDs.
        Set<UUID> requestedElementIds = request.elements().stream()
                .map(SaveVenueLayoutRequest.LayoutElementUpsert::elementId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (SaveVenueLayoutRequest.LayoutElementUpsert upsert : request.elements()) {
            JsonNode geometryNode = objectMapper.valueToTree(upsert.geometry());
            if (upsert.elementId() == null) {
                VenueLayoutElement created = VenueLayoutElement.builder()
                        .venue(venue)
                        .type(upsert.type())
                        .label(upsert.label())
                        .geometry(geometryNode)
                        .zIndex(upsert.zIndex())
                        .build();
                elementRepository.save(created);
            } else {
                VenueLayoutElement existing = elementById.get(upsert.elementId());
                existing.setType(upsert.type());
                existing.setLabel(upsert.label());
                existing.setGeometry(geometryNode);
                existing.setZIndex(upsert.zIndex());
            }
        }
        List<VenueLayoutElement> elementsToDelete = existingElements.stream()
                .filter(e -> e.getId() != null && !requestedElementIds.contains(e.getId()))
                .toList();
        if (!elementsToDelete.isEmpty()) {
            elementRepository.deleteAll(elementsToDelete);
        }

        // 8. Recalculate active-seat count from post-mutation entity state.
        long activeSeats = 0;
        Set<UUID> countedSectionIds = new HashSet<>();
        for (VenueSection section : allTargetSections) {
            if (section.getId() == null || !countedSectionIds.add(section.getId())) {
                continue;
            }
            if (!Boolean.TRUE.equals(section.getIsActive())) {
                continue;
            }
            List<Seat> seats = seatsBySectionId.getOrDefault(section.getId(), List.of());
            for (Seat seat : seats) {
                if (Boolean.TRUE.equals(seat.getIsActive())) {
                    activeSeats++;
                }
            }
        }
        if (activeSeats > venue.getCapacity()) {
            throw new ValidationException(
                    "Active seat count %d exceeds venue capacity %d".formatted(activeSeats, venue.getCapacity()),
                    ErrorCode.INVALID_REQUEST);
        }

        // 9. Increment layoutVersion exactly once, flush, map committed response.
        long oldVersion = venue.getLayoutVersion();
        venue.setLayoutVersion(oldVersion + 1);
        venueRepository.save(venue);

        sectionRepository.flush();
        seatRepository.flush();
        elementRepository.flush();
        venueRepository.flush();

        VenueSeatMapLayoutResponse response = buildEditorResponse(venue);

        // 10. Any exception above rolls back entity changes, element deletions and version.
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("Venue layout saved. venueId={}, oldVersion={}, newVersion={}, sectionCount={}, activeSeats={}, elementCount={}, durationMs={}",
                venueId, oldVersion, venue.getLayoutVersion(),
                response.sections().size(), activeSeats, response.elements().size(), durationMs);
        return response;
    }

    private void stageExistingUniquenessKeys(
            List<VenueSection> existingSections,
            Map<UUID, List<Seat>> seatsBySectionId,
            SaveVenueLayoutRequest request) {
        Set<String> unavailableNames = existingSections.stream()
                .map(VenueSection::getName)
                .collect(Collectors.toSet());
        request.sections().stream()
                .map(SaveVenueLayoutRequest.SectionUpsert::name)
                .forEach(unavailableNames::add);

        for (VenueSection section : existingSections) {
            String base = "__sf_stage_" + section.getId();
            String candidate = base;
            int suffix = 0;
            while (unavailableNames.contains(candidate)) {
                candidate = base + "_" + ++suffix;
            }
            unavailableNames.add(candidate);
            section.setName(candidate);
        }

        Set<SeatRowKey> unavailableRows = new HashSet<>();
        Set<SeatGridKey> unavailableGrids = new HashSet<>();
        for (List<Seat> seats : seatsBySectionId.values()) {
            for (Seat seat : seats) {
                unavailableRows.add(new SeatRowKey(seat.getSection().getId(), seat.getRowLabel(), seat.getSeatNumber()));
                unavailableGrids.add(new SeatGridKey(seat.getSection().getId(), seat.getGridX(), seat.getGridY()));
            }
        }
        for (SaveVenueLayoutRequest.SectionUpsert section : request.sections()) {
            if (section.sectionId() == null) {
                continue;
            }
            for (SaveVenueLayoutRequest.SeatUpsert seat : section.seats()) {
                unavailableRows.add(new SeatRowKey(
                        section.sectionId(), seat.rowLabel(), seat.seatNumber()));
                unavailableGrids.add(new SeatGridKey(
                        section.sectionId(), seat.gridX(), seat.gridY()));
            }
        }

        int rowNumber = 2_000_000_000;
        int gridX = 2_000_000_000;
        for (VenueSection section : existingSections) {
            for (Seat seat : seatsBySectionId.getOrDefault(section.getId(), List.of())) {
                SeatRowKey rowKey;
                do {
                    rowKey = new SeatRowKey(section.getId(), "__TMP__", rowNumber--);
                } while (unavailableRows.contains(rowKey));
                unavailableRows.add(rowKey);

                SeatGridKey gridKey;
                do {
                    gridKey = new SeatGridKey(section.getId(), gridX--, 2_000_000_000);
                } while (unavailableGrids.contains(gridKey));
                unavailableGrids.add(gridKey);

                seat.setRowLabel(rowKey.rowLabel());
                seat.setSeatNumber(rowKey.seatNumber());
                seat.setGridX(gridKey.gridX());
                seat.setGridY(gridKey.gridY());
                seat.setIsActive(false);
            }
        }

        sectionRepository.flush();
        seatRepository.flush();
    }

    private static void restoreSeatIdentity(Seat seat, SeatIdentity identity) {
        seat.setRowLabel(identity.rowLabel());
        seat.setSeatNumber(identity.seatNumber());
        seat.setGridX(identity.gridX());
        seat.setGridY(identity.gridY());
    }

    private static SaveVenueLayoutRequest canonicalize(SaveVenueLayoutRequest request) {
        List<SaveVenueLayoutRequest.SectionUpsert> sections = request.sections() == null
                ? null
                : request.sections().stream().map(section -> {
                    if (section == null) {
                        return null;
                    }
                    List<SaveVenueLayoutRequest.SeatUpsert> seats = section.seats() == null
                            ? null
                            : section.seats().stream().map(seat -> seat == null ? null
                            : new SaveVenueLayoutRequest.SeatUpsert(
                                    seat.seatId(), seat.rowLabel(), seat.seatNumber(), seat.gridX(), seat.gridY(),
                                    decimal3(seat.positionX()), decimal3(seat.positionY()), seat.isActive()))
                            .toList();
                    return new SaveVenueLayoutRequest.SectionUpsert(
                            section.sectionId(), section.name(), section.rowCount(), section.colCount(),
                            section.isActive(), decimal3(section.positionX()), decimal3(section.positionY()),
                            decimal3(section.width()), decimal3(section.height()), decimal3(section.rotationDeg()),
                            section.zIndex(), section.shapeMetadata(), seats);
                }).toList();
        return new SaveVenueLayoutRequest(request.layoutVersion(), sections, request.elements());
    }

    private static BigDecimal decimal3(BigDecimal value) {
        return value == null ? null : value.setScale(3, RoundingMode.HALF_UP);
    }

    private <T extends DomainEvent> void writeOutboxEvent(UUID aggregateId, String eventType, T eventPayload) {
        String correlationId = CorrelationContext.getCorrelationId().orElse(UUID.randomUUID().toString());
        EventEnvelope<T> base = EventEnvelope.of(eventType, aggregateId.toString(), correlationId, eventPayload);
        Map<String, String> headers = new HashMap<>();
        try {
            if (w3cTraceContextPropagator != null) {
                w3cTraceContextPropagator.inject(headers);
            }
        } catch (Exception ignored) {
        }
        EventEnvelope<T> envelope = base.withHeaders(headers);

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize EventEnvelope. aggregateId={}, eventType={}", aggregateId, eventType, e);
            throw new BusinessException(
                    "Failed to serialize domain event envelope", e,
                    ErrorCode.INTERNAL_SERVER_ERROR, 500);
        }

        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payloadJson)
                .build();
        outboxEventRepository.save(outboxEvent);
    }

    private record SectionIdentity(String name) {}

    private record SeatIdentity(String rowLabel, Integer seatNumber, Integer gridX, Integer gridY) {}

    private record SeatRowKey(UUID sectionId, String rowLabel, Integer seatNumber) {}

    private record SeatGridKey(UUID sectionId, Integer gridX, Integer gridY) {}

    private record CreatedSection(VenueSection section, SaveVenueLayoutRequest.SectionUpsert upsert) {}

    private LayoutValidationService.ExistingLayoutIds loadExistingIds(UUID venueId) {
        List<VenueSection> sections = sectionRepository.findByVenueIdOrderByZIndexAscNameAsc(venueId);
        Set<UUID> sectionIds = new HashSet<>();
        Map<UUID, UUID> seatToSection = new HashMap<>();
        for (VenueSection section : sections) {
            if (section.getId() != null) {
                sectionIds.add(section.getId());
                List<Seat> seats = seatRepository.findBySectionIdForEditor(section.getId());
                for (Seat seat : seats) {
                    if (seat.getId() != null) {
                        seatToSection.put(seat.getId(), section.getId());
                    }
                }
            }
        }
        Set<UUID> elementIds = elementRepository.findByVenueIdOrderByZIndexAscIdAsc(venueId).stream()
                .map(VenueLayoutElement::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return new LayoutValidationService.ExistingLayoutIds(sectionIds, seatToSection, elementIds);
    }

    private VenueSeatMapLayoutResponse buildEditorResponse(Venue venue) {
        UUID venueId = venue.getId();
        List<VenueSection> sections = sectionRepository.findByVenueIdOrderByZIndexAscNameAsc(venueId);
        List<SectionLayoutResponse> sectionResponses = new ArrayList<>(sections.size());
        long activeSeats = 0;
        for (VenueSection section : sections) {
            List<Seat> seats = seatRepository.findBySectionIdForEditor(section.getId());
            List<SeatResponse> seatResponses = seats.stream()
                    .map(seatMapper::toResponse)
                    .toList();
            if (Boolean.TRUE.equals(section.getIsActive())) {
                for (Seat seat : seats) {
                    if (Boolean.TRUE.equals(seat.getIsActive())) {
                        activeSeats++;
                    }
                }
            }
            sectionResponses.add(new SectionLayoutResponse(
                    section.getId(), section.getName(),
                    section.getRowCount(), section.getColCount(),
                    seatResponses,
                    section.getIsActive(),
                    section.getPositionX(), section.getPositionY(),
                    section.getWidth(), section.getHeight(),
                    section.getRotationDeg(), section.getZIndex(),
                    section.getShapeMetadata()));
        }
        List<LayoutElementResponse> elementResponses = elementRepository
                .findByVenueIdOrderByZIndexAscIdAsc(venueId).stream()
                .map(this::toElementResponse)
                .toList();
        return new VenueSeatMapLayoutResponse(
                venue.getId(), venue.getName(), venue.getCapacity(),
                activeSeats, sectionResponses,
                venue.getLayoutVersion(), elementResponses);
    }

    private LayoutElementResponse toElementResponse(VenueLayoutElement element) {
        JsonNode geometry = element.getGeometry();
        BigDecimal x = decimalField(geometry, "x");
        BigDecimal y = decimalField(geometry, "y");
        BigDecimal width = geometry != null && geometry.has("width")
                ? decimalField(geometry, "width")
                : decimalField(geometry, "w");
        BigDecimal height = geometry != null && geometry.has("height")
                ? decimalField(geometry, "height")
                : decimalField(geometry, "h");
        BigDecimal rotation = BigDecimal.ZERO;
        if (geometry != null) {
            if (geometry.has("rotationDeg")) {
                rotation = decimalField(geometry, "rotationDeg");
            } else if (geometry.has("rotation")) {
                rotation = decimalField(geometry, "rotation");
            } else if (geometry.has("rotation_deg")) {
                rotation = decimalField(geometry, "rotation_deg");
            }
        }
        return new LayoutElementResponse(
                element.getId(), element.getType(), element.getLabel(),
                new LayoutElementResponse.Geometry(x, y, width, height, rotation),
                element.getZIndex());
    }

    private static BigDecimal decimalField(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(node.get(field).asText());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }
}
