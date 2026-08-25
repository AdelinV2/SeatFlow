package com.seatflow.seatmap.service;

import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.seatmap.mapper.SeatMapper;
import com.seatflow.seatmap.model.entity.Seat;
import com.seatflow.seatmap.model.entity.Venue;
import com.seatflow.seatmap.model.entity.VenueSection;
import com.seatflow.seatmap.repository.SeatRepository;
import com.seatflow.seatmap.repository.VenueRepository;
import com.seatflow.seatmap.repository.VenueSectionRepository;
import com.seatflow.seatmap.service.impl.SeatMapLayoutServiceImpl;
import com.seatflow.seatmap.web.dto.response.SeatResponse;
import com.seatflow.seatmap.web.dto.response.VenueSeatMapLayoutResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatMapLayoutServiceImplTest {

    @InjectMocks
    private SeatMapLayoutServiceImpl layoutService;

    @Mock
    private VenueRepository venueRepository;

    @Mock
    private VenueSectionRepository sectionRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private SeatMapper seatMapper;

    @Test
    void shouldReturnCompleteVenueLayout() {
        // Given
        UUID venueId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        Venue venue = Venue.builder().id(venueId).name("Test Venue").capacity(100)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        VenueSection section = VenueSection.builder().id(sectionId).venue(venue)
                .name("Orchestra").rowCount(2).colCount(3).createdAt(Instant.now()).updatedAt(Instant.now()).build();
        Seat seat = Seat.builder().id(UUID.randomUUID()).section(section)
                .rowLabel("A").seatNumber(1).gridX(0).gridY(0).isActive(true).createdAt(Instant.now()).build();

        when(venueRepository.findById(venueId)).thenReturn(Optional.of(venue));
        when(sectionRepository.findByVenueIdOrderByNameAsc(venueId)).thenReturn(List.of(section));
        when(seatRepository.findActiveSeatsBySectionId(sectionId)).thenReturn(List.of(seat));
        when(seatMapper.toResponse(any(Seat.class))).thenReturn(
                new SeatResponse(seat.getId(), "A", 1, 0, 0, true));

        // When
        VenueSeatMapLayoutResponse result = layoutService.getVenueLayout(venueId);

        // Then
        assertThat(result.venueId()).isEqualTo(venueId);
        assertThat(result.name()).isEqualTo("Test Venue");
        assertThat(result.sections()).hasSize(1);
        assertThat(result.sections().getFirst().seats()).hasSize(1);
    }

    @Test
    void shouldThrowNotFoundForNonExistentVenue() {
        UUID venueId = UUID.randomUUID();
        when(venueRepository.findById(venueId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> layoutService.getVenueLayout(venueId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
