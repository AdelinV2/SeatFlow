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
                            .findBySectionIdOrderByGridYAscGridXAsc(section.getId())
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

        long totalConfiguredSeats = sectionLayouts.stream()
                .mapToLong(s -> s.seats().stream().filter(seat -> Boolean.TRUE.equals(seat.isActive())).count())
                .sum();

        log.debug("Venue layout retrieved. venueId={}, name={}, sectionCount={}, totalConfiguredSeats={}",
                venueId, venue.getName(), sectionLayouts.size(), totalConfiguredSeats);

        return new VenueSeatMapLayoutResponse(
                venue.getId(), venue.getName(),
                venue.getCapacity(), totalConfiguredSeats, sectionLayouts
        );
    }
}
