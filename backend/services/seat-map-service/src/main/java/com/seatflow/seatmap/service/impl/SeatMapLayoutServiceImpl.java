package com.seatflow.seatmap.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.seatmap.mapper.SeatMapper;
import com.seatflow.seatmap.model.entity.Venue;
import com.seatflow.seatmap.model.entity.VenueLayoutElement;
import com.seatflow.seatmap.model.entity.VenueSection;
import com.seatflow.seatmap.repository.SeatRepository;
import com.seatflow.seatmap.repository.VenueLayoutElementRepository;
import com.seatflow.seatmap.repository.VenueRepository;
import com.seatflow.seatmap.repository.VenueSectionRepository;
import com.seatflow.seatmap.service.SeatMapLayoutService;
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
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeatMapLayoutServiceImpl implements SeatMapLayoutService {

    private final VenueRepository venueRepository;
    private final VenueSectionRepository sectionRepository;
    private final SeatRepository seatRepository;
    private final VenueLayoutElementRepository elementRepository;
    private final SeatMapper seatMapper;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VenueSeatMapLayoutResponse getVenueLayout(UUID venueId) {
        Venue venue = venueRepository.findById(venueId)
                .orElseThrow(() -> new ResourceNotFoundException("Venue not found: " + venueId));

        // Public read exposes only active sections to keep legacy customer rendering stable.
        // Inactive seats inside active sections remain explicit so seat IDs/status stay visible.
        List<VenueSection> sections =
                sectionRepository.findByVenueIdAndIsActiveTrueOrderByZIndexAscNameAsc(venueId);

        List<SectionLayoutResponse> sectionLayouts = sections.stream()
                .map(section -> {
                    List<SeatResponse> seatResponses = seatRepository
                            .findBySectionIdOrderByGridYAscGridXAsc(section.getId())
                            .stream()
                            .map(seatMapper::toResponse)
                            .toList();

                    return new SectionLayoutResponse(
                            section.getId(), section.getName(),
                            section.getRowCount(), section.getColCount(),
                            seatResponses,
                            section.getIsActive(),
                            section.getPositionX(), section.getPositionY(),
                            section.getWidth(), section.getHeight(),
                            section.getRotationDeg(), section.getZIndex(),
                            section.getShapeMetadata()
                    );
                })
                .toList();

        long totalConfiguredSeats = sectionLayouts.stream()
                .mapToLong(s -> s.seats().stream().filter(seat -> Boolean.TRUE.equals(seat.isActive())).count())
                .sum();

        log.debug("Venue layout retrieved. venueId={}, name={}, sectionCount={}, totalConfiguredSeats={}",
                venueId, venue.getName(), sectionLayouts.size(), totalConfiguredSeats);

        List<LayoutElementResponse> elements = elementRepository
                .findByVenueIdOrderByZIndexAscIdAsc(venueId).stream()
                .map(this::toElementResponse)
                .toList();

        return new VenueSeatMapLayoutResponse(
                venue.getId(), venue.getName(),
                venue.getCapacity(), totalConfiguredSeats, sectionLayouts,
                venue.getLayoutVersion(), elements
        );
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
