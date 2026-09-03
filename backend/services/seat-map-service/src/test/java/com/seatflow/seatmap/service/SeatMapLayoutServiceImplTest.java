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
        Seat seat1 = Seat.builder().id(UUID.randomUUID()).section(section)
                .rowLabel("A").seatNumber(1).gridX(0).gridY(0).isActive(true).createdAt(Instant.now()).build();
        Seat seat2 = Seat.builder().id(UUID.randomUUID()).section(section)
                .rowLabel("A").seatNumber(2).gridX(1).gridY(0).isActive(false).createdAt(Instant.now()).build();

        when(venueRepository.findById(venueId)).thenReturn(Optional.of(venue));
        when(sectionRepository.findByVenueIdOrderByNameAsc(venueId)).thenReturn(List.of(section));
        when(seatRepository.findBySectionIdOrderByGridYAscGridXAsc(sectionId)).thenReturn(List.of(seat1, seat2));
        when(seatMapper.toResponse(seat1)).thenReturn(
                new SeatResponse(seat1.getId(), "A", 1, 0, 0, true,
                        new java.math.BigDecimal("0.000"), new java.math.BigDecimal("0.000")));
        when(seatMapper.toResponse(seat2)).thenReturn(
                new SeatResponse(seat2.getId(), "A", 2, 1, 0, false,
                        new java.math.BigDecimal("44.000"), new java.math.BigDecimal("0.000")));

        // When
        VenueSeatMapLayoutResponse result = layoutService.getVenueLayout(venueId);

        // Then
        assertThat(result.venueId()).isEqualTo(venueId);
        assertThat(result.name()).isEqualTo("Test Venue");
        assertThat(result.sections()).hasSize(1);
        assertThat(result.sections().getFirst().seats()).hasSize(2);
        assertThat(result.totalConfiguredSeats()).isEqualTo(1); // Only 1 active seat
    }

    @Test
    void shouldThrowNotFoundForNonExistentVenue() {
        UUID venueId = UUID.randomUUID();
        when(venueRepository.findById(venueId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> layoutService.getVenueLayout(venueId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
