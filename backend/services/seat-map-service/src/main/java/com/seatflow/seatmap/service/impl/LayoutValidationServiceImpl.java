package com.seatflow.seatmap.service.impl;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.seatmap.model.entity.Venue;
import com.seatflow.seatmap.model.enums.LayoutElementType;
import com.seatflow.seatmap.service.LayoutValidationService;
import com.seatflow.seatmap.web.dto.request.SaveVenueLayoutRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class LayoutValidationServiceImpl implements LayoutValidationService {

    private static final BigDecimal POS_MIN = BigDecimal.ZERO;
    private static final BigDecimal POS_MAX = new BigDecimal("100000");
    private static final BigDecimal ROT_MIN = new BigDecimal("-180");
    private static final BigDecimal ROT_MAX = new BigDecimal("180");
    private static final int Z_MIN = -1000;
    private static final int Z_MAX = 1000;

    @Override
    public void validate(Venue venue, SaveVenueLayoutRequest request, ExistingLayoutIds ids) {
        if (venue == null) {
            throw new ValidationException("Rule 10: venue is required", ErrorCode.INVALID_REQUEST);
        }
        if (request == null) {
            throw new ValidationException("Rule 14: layout request is required", ErrorCode.INVALID_REQUEST);
        }
        ExistingLayoutIds snapshot = ids == null ? ExistingLayoutIds.empty() : ids;

        List<SaveVenueLayoutRequest.SectionUpsert> sections = request.sections();
        List<SaveVenueLayoutRequest.LayoutElementUpsert> elements = request.elements();
        int sectionCount = sections == null ? -1 : sections.size();
        int elementCount = elements == null ? -1 : elements.size();
        log.info("Validating venue layout. venueId={}, layoutVersion={}, sectionCount={}, elementCount={}",
                venue.getId(), request.layoutVersion(), sectionCount, elementCount);

        try {
            // Rule 1: trim names/row labels; reject blank-after-trim.
            validateRule1Trim(sections);
            // Rule 2: section names case-insensitively unique across active sections.
            validateRule2SectionNames(sections);
            // Rule 3: non-null section IDs unique and owned by venue.
            validateRule3SectionIds(sections, snapshot);
            // Rule 4: non-null seat IDs unique and owned by matching section; no moves.
            validateRule4SeatIds(sections, snapshot);
            // Rule 5: per-section (upper(rowLabel), seatNumber) and (gridX, gridY) uniqueness.
            validateRule5RowGrid(sections);
            // Rule 6: per-section active (positionX, positionY) uniqueness after stripTrailingZeros.
            validateRule6ActivePositions(sections);
            // Rule 7: section V5 bounds.
            validateRule7SectionBounds(sections);
            // Rule 8: seat local bounds and grid bounds.
            validateRule8SeatBounds(sections);
            // Rule 9: shapeMetadata null or JSON object.
            validateRule9ShapeMetadata(sections);
            // Rule 10: capacity and inactive-section active-seat rejection.
            validateRule10Capacity(venue, sections);
            // Rule 11: element geometry and z-index bounds.
            validateRule11ElementBounds(elements);
            // Rule 12: LABEL requires label; labels trimmed and <= 255.
            validateRule12ElementLabels(elements);
            // Rule 13: element IDs unique and owned by venue.
            validateRule13ElementIds(elements, snapshot);
            // Rule 14: zero sections/elements allowed; null entries rejected.
            validateRule14NullEntries(request, sections, elements);
        } catch (ValidationException ex) {
            log.warn("Venue layout validation failed. venueId={}, layoutVersion={}, sectionCount={}, elementCount={}, rule={}",
                    venue.getId(), request.layoutVersion(), sectionCount, elementCount, ex.getMessage());
            throw ex;
        }

        long activeSeats = countActiveSeats(sections);
        log.info("Venue layout validation passed. venueId={}, layoutVersion={}, sectionCount={}, elementCount={}, activeSeats={}",
                venue.getId(), request.layoutVersion(), sectionCount, elementCount, activeSeats);
    }

    // Rule 1
    private void validateRule1Trim(List<SaveVenueLayoutRequest.SectionUpsert> sections) {
        if (sections == null) {
            return;
        }
        for (int i = 0; i < sections.size(); i++) {
            SaveVenueLayoutRequest.SectionUpsert section = sections.get(i);
            if (section == null) {
                continue;
            }
            String name = section.name();
            if (name == null || name.trim().isEmpty()) {
                throw new ValidationException(
                        "Rule 1: sections[" + i + "].name must not be blank", ErrorCode.INVALID_REQUEST);
            }
            List<SaveVenueLayoutRequest.SeatUpsert> seats = section.seats();
            if (seats == null) {
                continue;
            }
            for (int j = 0; j < seats.size(); j++) {
                SaveVenueLayoutRequest.SeatUpsert seat = seats.get(j);
                if (seat == null) {
                    continue;
                }
                String rowLabel = seat.rowLabel();
                if (rowLabel == null || rowLabel.trim().isEmpty()) {
                    throw new ValidationException(
                            "Rule 1: sections[" + i + "].seats[" + j + "].rowLabel must not be blank",
                            ErrorCode.INVALID_REQUEST);
                }
            }
        }
    }

    // Rule 2
    private void validateRule2SectionNames(List<SaveVenueLayoutRequest.SectionUpsert> sections) {
        if (sections == null) {
            return;
        }
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < sections.size(); i++) {
            SaveVenueLayoutRequest.SectionUpsert section = sections.get(i);
            if (section == null) {
                continue;
            }
            if (!Boolean.TRUE.equals(section.isActive())) {
                if (section.isActive() == null) {
                    throw new ValidationException(
                            "Rule 2: sections[" + i + "].isActive is required", ErrorCode.INVALID_REQUEST);
                }
                continue;
            }
            String name = section.name();
            if (name == null) {
                continue;
            }
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            Integer first = seen.putIfAbsent(normalized, i);
            if (first != null) {
                throw new ValidationException(
                        "Rule 2: sections[" + i + "].name duplicates sections[" + first + "].name",
                        ErrorCode.INVALID_REQUEST);
            }
        }
    }

    // Rule 3
    private void validateRule3SectionIds(List<SaveVenueLayoutRequest.SectionUpsert> sections,
                                         ExistingLayoutIds ids) {
        if (sections == null) {
            return;
        }
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < sections.size(); i++) {
            SaveVenueLayoutRequest.SectionUpsert section = sections.get(i);
            if (section == null) {
                continue;
            }
            UUID sectionId = section.sectionId();
            if (sectionId == null) {
                continue;
            }
            if (!seen.add(sectionId)) {
                throw new ValidationException(
                        "Rule 3: sections[" + i + "].sectionId is duplicated", ErrorCode.INVALID_REQUEST);
            }
            if (!ids.sectionIds().contains(sectionId)) {
                throw new ValidationException(
                        "Rule 3: sections[" + i + "].sectionId does not belong to the target venue",
                        ErrorCode.INVALID_REQUEST);
            }
        }
    }

    // Rule 4
    private void validateRule4SeatIds(List<SaveVenueLayoutRequest.SectionUpsert> sections,
                                      ExistingLayoutIds ids) {
        if (sections == null) {
            return;
        }
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < sections.size(); i++) {
            SaveVenueLayoutRequest.SectionUpsert section = sections.get(i);
            if (section == null) {
                continue;
            }
            List<SaveVenueLayoutRequest.SeatUpsert> seats = section.seats();
            if (seats == null) {
                continue;
            }
            for (int j = 0; j < seats.size(); j++) {
                SaveVenueLayoutRequest.SeatUpsert seat = seats.get(j);
                if (seat == null) {
                    continue;
                }
                UUID seatId = seat.seatId();
                if (seatId == null) {
                    continue;
                }
                if (!seen.add(seatId)) {
                    throw new ValidationException(
                            "Rule 4: sections[" + i + "].seats[" + j + "].seatId is duplicated",
                            ErrorCode.INVALID_REQUEST);
                }
                UUID owningSectionId = ids.seatIdToSectionId().get(seatId);
                if (owningSectionId == null) {
                    throw new ValidationException(
                            "Rule 4: sections[" + i + "].seats[" + j + "].seatId does not belong to the target venue",
                            ErrorCode.INVALID_REQUEST);
                }
                UUID currentSectionId = section.sectionId();
                if (currentSectionId == null || !owningSectionId.equals(currentSectionId)) {
                    throw new ValidationException(
                            "Rule 4: sections[" + i + "].seats[" + j + "].seatId belongs to another section",
                            ErrorCode.INVALID_REQUEST);
                }
            }
        }
    }

    // Rule 5
    private void validateRule5RowGrid(List<SaveVenueLayoutRequest.SectionUpsert> sections) {
        if (sections == null) {
            return;
        }
        for (int i = 0; i < sections.size(); i++) {
            SaveVenueLayoutRequest.SectionUpsert section = sections.get(i);
            if (section == null || section.seats() == null) {
                continue;
            }
            Set<String> rowKeys = new HashSet<>();
            Set<String> gridKeys = new HashSet<>();
            List<SaveVenueLayoutRequest.SeatUpsert> seats = section.seats();
            for (int j = 0; j < seats.size(); j++) {
                SaveVenueLayoutRequest.SeatUpsert seat = seats.get(j);
                if (seat == null) {
                    continue;
                }
                if (seat.rowLabel() == null || seat.seatNumber() == null) {
                    throw new ValidationException(
                            "Rule 5: sections[" + i + "].seats[" + j + "].rowLabel/seatNumber is required",
                            ErrorCode.INVALID_REQUEST);
                }
                if (seat.gridX() == null || seat.gridY() == null) {
                    throw new ValidationException(
                            "Rule 5: sections[" + i + "].seats[" + j + "].gridX/gridY is required",
                            ErrorCode.INVALID_REQUEST);
                }
                String rowKey = seat.rowLabel().trim().toUpperCase(Locale.ROOT) + "|" + seat.seatNumber();
                if (!rowKeys.add(rowKey)) {
                    throw new ValidationException(
                            "Rule 5: sections[" + i + "].seats[" + j + "] duplicates rowLabel/seatNumber",
                            ErrorCode.INVALID_REQUEST);
                }
                String gridKey = seat.gridX() + "," + seat.gridY();
                if (!gridKeys.add(gridKey)) {
                    throw new ValidationException(
                            "Rule 5: sections[" + i + "].seats[" + j + "] duplicates gridX/gridY",
                            ErrorCode.INVALID_REQUEST);
                }
            }
        }
    }

    // Rule 6
    private void validateRule6ActivePositions(List<SaveVenueLayoutRequest.SectionUpsert> sections) {
        if (sections == null) {
            return;
        }
        for (int i = 0; i < sections.size(); i++) {
            SaveVenueLayoutRequest.SectionUpsert section = sections.get(i);
            if (section == null || section.seats() == null) {
                continue;
            }
            Set<PositionKey> seen = new HashSet<>();
            List<SaveVenueLayoutRequest.SeatUpsert> seats = section.seats();
            for (int j = 0; j < seats.size(); j++) {
                SaveVenueLayoutRequest.SeatUpsert seat = seats.get(j);
                if (seat == null) {
                    continue;
                }
                if (!Boolean.TRUE.equals(seat.isActive())) {
                    if (seat.isActive() == null) {
                        throw new ValidationException(
                                "Rule 6: sections[" + i + "].seats[" + j + "].isActive is required",
                                ErrorCode.INVALID_REQUEST);
                    }
                    continue;
                }
                if (seat.positionX() == null || seat.positionY() == null) {
                    throw new ValidationException(
                            "Rule 6: sections[" + i + "].seats[" + j + "].positionX/positionY is required",
                            ErrorCode.INVALID_REQUEST);
                }
                PositionKey key = new PositionKey(
                        seat.positionX().stripTrailingZeros(), seat.positionY().stripTrailingZeros());
                if (!seen.add(key)) {
                    throw new ValidationException(
                            "Rule 6: sections[" + i + "].seats[" + j + "] duplicates active positionX/positionY",
                            ErrorCode.INVALID_REQUEST);
                }
            }
        }
    }

    private record PositionKey(BigDecimal x, BigDecimal y) {}

    // Rule 7
    private void validateRule7SectionBounds(List<SaveVenueLayoutRequest.SectionUpsert> sections) {
        if (sections == null) {
            return;
        }
        for (int i = 0; i < sections.size(); i++) {
            SaveVenueLayoutRequest.SectionUpsert section = sections.get(i);
            if (section == null) {
                continue;
            }
            if (section.positionX() == null || section.positionY() == null
                    || section.width() == null || section.height() == null
                    || section.rotationDeg() == null || section.zIndex() == null) {
                throw new ValidationException(
                        "Rule 7: sections[" + i + "] position/width/height/rotation/zIndex is required",
                        ErrorCode.INVALID_REQUEST);
            }
            if (!inRangeInclusive(section.positionX(), POS_MIN, POS_MAX)) {
                throw new ValidationException(
                        "Rule 7: sections[" + i + "].positionX out of bounds 0..100000",
                        ErrorCode.INVALID_REQUEST);
            }
            if (!inRangeInclusive(section.positionY(), POS_MIN, POS_MAX)) {
                throw new ValidationException(
                        "Rule 7: sections[" + i + "].positionY out of bounds 0..100000",
                        ErrorCode.INVALID_REQUEST);
            }
            if (!(section.width().compareTo(BigDecimal.ZERO) > 0
                    && section.width().compareTo(POS_MAX) <= 0)) {
                throw new ValidationException(
                        "Rule 7: sections[" + i + "].width out of bounds (>0..100000)",
                        ErrorCode.INVALID_REQUEST);
            }
            if (!(section.height().compareTo(BigDecimal.ZERO) > 0
                    && section.height().compareTo(POS_MAX) <= 0)) {
                throw new ValidationException(
                        "Rule 7: sections[" + i + "].height out of bounds (>0..100000)",
                        ErrorCode.INVALID_REQUEST);
            }
            if (!inRangeInclusive(section.rotationDeg(), ROT_MIN, ROT_MAX)) {
                throw new ValidationException(
                        "Rule 7: sections[" + i + "].rotationDeg out of bounds -180..180",
                        ErrorCode.INVALID_REQUEST);
            }
            if (section.zIndex() < Z_MIN || section.zIndex() > Z_MAX) {
                throw new ValidationException(
                        "Rule 7: sections[" + i + "].zIndex out of bounds -1000..1000",
                        ErrorCode.INVALID_REQUEST);
            }
        }
    }

    // Rule 8
    private void validateRule8SeatBounds(List<SaveVenueLayoutRequest.SectionUpsert> sections) {
        if (sections == null) {
            return;
        }
        for (int i = 0; i < sections.size(); i++) {
            SaveVenueLayoutRequest.SectionUpsert section = sections.get(i);
            if (section == null) {
                continue;
            }
            if (section.rowCount() == null || section.rowCount() < 1
                    || section.colCount() == null || section.colCount() < 1) {
                throw new ValidationException(
                        "Rule 8: sections[" + i + "].rowCount/colCount must be at least 1",
                        ErrorCode.INVALID_REQUEST);
            }
            if (section.width() == null || section.height() == null) {
                throw new ValidationException(
                        "Rule 8: sections[" + i + "].width/height is required",
                        ErrorCode.INVALID_REQUEST);
            }
            List<SaveVenueLayoutRequest.SeatUpsert> seats = section.seats();
            if (seats == null) {
                continue;
            }
            for (int j = 0; j < seats.size(); j++) {
                SaveVenueLayoutRequest.SeatUpsert seat = seats.get(j);
                if (seat == null) {
                    continue;
                }
                if (seat.positionX() == null || seat.positionY() == null) {
                    throw new ValidationException(
                            "Rule 8: sections[" + i + "].seats[" + j + "].positionX/positionY is required",
                            ErrorCode.INVALID_REQUEST);
                }
                if (seat.gridX() == null || seat.gridY() == null) {
                    throw new ValidationException(
                            "Rule 8: sections[" + i + "].seats[" + j + "].gridX/gridY is required",
                            ErrorCode.INVALID_REQUEST);
                }
                if (!inRangeInclusive(seat.positionX(), POS_MIN, section.width())) {
                    throw new ValidationException(
                            "Rule 8: sections[" + i + "].seats[" + j + "].positionX out of section bounds",
                            ErrorCode.INVALID_REQUEST);
                }
                if (!inRangeInclusive(seat.positionY(), POS_MIN, section.height())) {
                    throw new ValidationException(
                            "Rule 8: sections[" + i + "].seats[" + j + "].positionY out of section bounds",
                            ErrorCode.INVALID_REQUEST);
                }
                if (seat.gridX() < 0 || seat.gridX() >= section.colCount()) {
                    throw new ValidationException(
                            "Rule 8: sections[" + i + "].seats[" + j + "].gridX out of bounds",
                            ErrorCode.INVALID_REQUEST);
                }
                if (seat.gridY() < 0 || seat.gridY() >= section.rowCount()) {
                    throw new ValidationException(
                            "Rule 8: sections[" + i + "].seats[" + j + "].gridY out of bounds",
                            ErrorCode.INVALID_REQUEST);
                }
            }
        }
    }

    // Rule 9
    private void validateRule9ShapeMetadata(List<SaveVenueLayoutRequest.SectionUpsert> sections) {
        if (sections == null) {
            return;
        }
        for (int i = 0; i < sections.size(); i++) {
            SaveVenueLayoutRequest.SectionUpsert section = sections.get(i);
            if (section == null) {
                continue;
            }
            if (section.shapeMetadata() == null || section.shapeMetadata().isNull()) {
                continue;
            }
            if (!section.shapeMetadata().isObject()) {
                throw new ValidationException(
                        "Rule 9: sections[" + i + "].shapeMetadata must be a JSON object",
                        ErrorCode.INVALID_REQUEST);
            }
        }
    }

    // Rule 10
    private void validateRule10Capacity(Venue venue,
                                        List<SaveVenueLayoutRequest.SectionUpsert> sections) {
        if (venue.getCapacity() == null) {
            throw new ValidationException("Rule 10: venue capacity is required", ErrorCode.INVALID_REQUEST);
        }
        if (sections == null) {
            return;
        }
        long activeSeats = 0;
        for (int i = 0; i < sections.size(); i++) {
            SaveVenueLayoutRequest.SectionUpsert section = sections.get(i);
            if (section == null || section.seats() == null) {
                continue;
            }
            boolean sectionActive = Boolean.TRUE.equals(section.isActive());
            for (int j = 0; j < section.seats().size(); j++) {
                SaveVenueLayoutRequest.SeatUpsert seat = section.seats().get(j);
                if (seat == null) {
                    continue;
                }
                if (Boolean.TRUE.equals(seat.isActive())) {
                    if (!sectionActive) {
                        throw new ValidationException(
                                "Rule 10: sections[" + i + "].seats[" + j + "] is active inside an inactive section",
                                ErrorCode.INVALID_REQUEST);
                    }
                    activeSeats++;
                }
            }
        }
        if (activeSeats > venue.getCapacity()) {
            throw new ValidationException(
                    "Rule 10: active seat count exceeds venue capacity", ErrorCode.INVALID_REQUEST);
        }
    }

    // Rule 11
    private void validateRule11ElementBounds(List<SaveVenueLayoutRequest.LayoutElementUpsert> elements) {
        if (elements == null) {
            return;
        }
        for (int i = 0; i < elements.size(); i++) {
            SaveVenueLayoutRequest.LayoutElementUpsert element = elements.get(i);
            if (element == null) {
                continue;
            }
            if (element.geometry() == null) {
                throw new ValidationException(
                        "Rule 11: elements[" + i + "].geometry is required", ErrorCode.INVALID_REQUEST);
            }
            if (element.zIndex() == null) {
                throw new ValidationException(
                        "Rule 11: elements[" + i + "].zIndex is required", ErrorCode.INVALID_REQUEST);
            }
            SaveVenueLayoutRequest.Geometry geometry = element.geometry();
            if (geometry.x() == null || geometry.y() == null || geometry.width() == null
                    || geometry.height() == null || geometry.rotationDeg() == null) {
                throw new ValidationException(
                        "Rule 11: elements[" + i + "].geometry fields are required",
                        ErrorCode.INVALID_REQUEST);
            }
            if (!inRangeInclusive(geometry.x(), POS_MIN, POS_MAX)) {
                throw new ValidationException(
                        "Rule 11: elements[" + i + "].geometry.x out of bounds 0..100000",
                        ErrorCode.INVALID_REQUEST);
            }
            if (!inRangeInclusive(geometry.y(), POS_MIN, POS_MAX)) {
                throw new ValidationException(
                        "Rule 11: elements[" + i + "].geometry.y out of bounds 0..100000",
                        ErrorCode.INVALID_REQUEST);
            }
            if (!(geometry.width().compareTo(BigDecimal.ZERO) > 0
                    && geometry.width().compareTo(POS_MAX) <= 0)) {
                throw new ValidationException(
                        "Rule 11: elements[" + i + "].geometry.width out of bounds (>0..100000)",
                        ErrorCode.INVALID_REQUEST);
            }
            if (!(geometry.height().compareTo(BigDecimal.ZERO) > 0
                    && geometry.height().compareTo(POS_MAX) <= 0)) {
                throw new ValidationException(
                        "Rule 11: elements[" + i + "].geometry.height out of bounds (>0..100000)",
                        ErrorCode.INVALID_REQUEST);
            }
            if (!inRangeInclusive(geometry.rotationDeg(), ROT_MIN, ROT_MAX)) {
                throw new ValidationException(
                        "Rule 11: elements[" + i + "].geometry.rotationDeg out of bounds -180..180",
                        ErrorCode.INVALID_REQUEST);
            }
            if (element.zIndex() < Z_MIN || element.zIndex() > Z_MAX) {
                throw new ValidationException(
                        "Rule 11: elements[" + i + "].zIndex out of bounds -1000..1000",
                        ErrorCode.INVALID_REQUEST);
            }
        }
    }

    // Rule 12
    private void validateRule12ElementLabels(List<SaveVenueLayoutRequest.LayoutElementUpsert> elements) {
        if (elements == null) {
            return;
        }
        for (int i = 0; i < elements.size(); i++) {
            SaveVenueLayoutRequest.LayoutElementUpsert element = elements.get(i);
            if (element == null) {
                continue;
            }
            if (element.type() == null) {
                throw new ValidationException(
                        "Rule 12: elements[" + i + "].type is required", ErrorCode.INVALID_REQUEST);
            }
            String label = element.label();
            String trimmed = label == null ? null : label.trim();
            if (element.type() == LayoutElementType.LABEL) {
                if (trimmed == null || trimmed.isEmpty()) {
                    throw new ValidationException(
                            "Rule 12: elements[" + i + "].label is required for LABEL type",
                            ErrorCode.INVALID_REQUEST);
                }
                if (trimmed.length() > 255) {
                    throw new ValidationException(
                            "Rule 12: elements[" + i + "].label must not exceed 255 characters",
                            ErrorCode.INVALID_REQUEST);
                }
            } else {
                if (trimmed != null && trimmed.length() > 255) {
                    throw new ValidationException(
                            "Rule 12: elements[" + i + "].label must not exceed 255 characters",
                            ErrorCode.INVALID_REQUEST);
                }
            }
        }
    }

    // Rule 13
    private void validateRule13ElementIds(List<SaveVenueLayoutRequest.LayoutElementUpsert> elements,
                                          ExistingLayoutIds ids) {
        if (elements == null) {
            return;
        }
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < elements.size(); i++) {
            SaveVenueLayoutRequest.LayoutElementUpsert element = elements.get(i);
            if (element == null) {
                continue;
            }
            UUID elementId = element.elementId();
            if (elementId == null) {
                continue;
            }
            if (!seen.add(elementId)) {
                throw new ValidationException(
                        "Rule 13: elements[" + i + "].elementId is duplicated", ErrorCode.INVALID_REQUEST);
            }
            if (!ids.elementIds().contains(elementId)) {
                throw new ValidationException(
                        "Rule 13: elements[" + i + "].elementId does not belong to the target venue",
                        ErrorCode.INVALID_REQUEST);
            }
        }
    }

    // Rule 14
    private void validateRule14NullEntries(SaveVenueLayoutRequest request,
                                           List<SaveVenueLayoutRequest.SectionUpsert> sections,
                                           List<SaveVenueLayoutRequest.LayoutElementUpsert> elements) {
        if (sections == null || elements == null) {
            throw new ValidationException(
                    "Rule 14: sections and elements lists are required", ErrorCode.INVALID_REQUEST);
        }
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i) == null) {
                throw new ValidationException(
                        "Rule 14: sections[" + i + "] must not be null", ErrorCode.INVALID_REQUEST);
            }
            List<SaveVenueLayoutRequest.SeatUpsert> seats = sections.get(i).seats();
            if (seats == null) {
                throw new ValidationException(
                        "Rule 14: sections[" + i + "].seats list is required", ErrorCode.INVALID_REQUEST);
            }
            for (int j = 0; j < seats.size(); j++) {
                if (seats.get(j) == null) {
                    throw new ValidationException(
                            "Rule 14: sections[" + i + "].seats[" + j + "] must not be null",
                            ErrorCode.INVALID_REQUEST);
                }
            }
        }
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i) == null) {
                throw new ValidationException(
                        "Rule 14: elements[" + i + "] must not be null", ErrorCode.INVALID_REQUEST);
            }
        }
        if (request.layoutVersion() == null) {
            throw new ValidationException(
                    "Rule 14: layoutVersion is required", ErrorCode.INVALID_REQUEST);
        }
    }

    private long countActiveSeats(List<SaveVenueLayoutRequest.SectionUpsert> sections) {
        if (sections == null) {
            return 0;
        }
        long count = 0;
        for (SaveVenueLayoutRequest.SectionUpsert section : sections) {
            if (section == null || !Boolean.TRUE.equals(section.isActive()) || section.seats() == null) {
                continue;
            }
            for (SaveVenueLayoutRequest.SeatUpsert seat : section.seats()) {
                if (seat != null && Boolean.TRUE.equals(seat.isActive())) {
                    count++;
                }
            }
        }
        return count;
    }

    private boolean inRangeInclusive(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }
}
