package com.seatflow.seatmap.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.seatmap.mapper.SeatMapper;
import com.seatflow.seatmap.mapper.VenueSectionMapper;
import com.seatflow.seatmap.model.entity.OutboxEvent;
import com.seatflow.seatmap.model.entity.Seat;
import com.seatflow.seatmap.model.entity.Venue;
import com.seatflow.seatmap.model.entity.VenueSection;
import com.seatflow.seatmap.repository.OutboxEventRepository;
import com.seatflow.seatmap.repository.SeatRepository;
import com.seatflow.seatmap.repository.VenueRepository;
import com.seatflow.seatmap.repository.VenueSectionRepository;
import com.seatflow.seatmap.service.impl.VenueSectionServiceImpl;
import com.seatflow.seatmap.web.dto.request.CreateVenueSectionRequest;
import com.seatflow.seatmap.web.dto.request.UpdateSeatStatusRequest;
import com.seatflow.seatmap.web.dto.response.SeatResponse;
import com.seatflow.seatmap.web.dto.response.VenueSectionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VenueSectionServiceImplTest {

    @InjectMocks
    private VenueSectionServiceImpl sectionService;

    @Mock
    private VenueRepository venueRepository;

    @Mock
    private VenueSectionRepository sectionRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private SeatMapper seatMapper;

    @Mock
    private VenueSectionMapper venueSectionMapper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldCreateSectionAndGenerateSeatGrid() {
        // Given
        UUID venueId = UUID.randomUUID();
        Venue venue = Venue.builder().id(venueId).name("Test Venue").address("123 St")
                .city("NYC").country("USA").capacity(100).build();
        CreateVenueSectionRequest request = new CreateVenueSectionRequest("Orchestra", 3, 5);
        UUID sectionId = UUID.randomUUID();
        VenueSection savedSection = VenueSection.builder().id(sectionId).venue(venue)
                .name("Orchestra").rowCount(3).colCount(5).createdAt(Instant.now()).updatedAt(Instant.now()).build();
        VenueSectionResponse expectedResponse = new VenueSectionResponse(sectionId, "Orchestra", 3, 5, 15L);

        when(venueRepository.findById(venueId)).thenReturn(Optional.of(venue));
        when(sectionRepository.existsByVenueIdAndName(venueId, "Orchestra")).thenReturn(false);
        when(seatRepository.countActiveSeatsByVenueId(venueId)).thenReturn(0L);
        when(sectionRepository.save(any(VenueSection.class))).thenReturn(savedSection);
        when(seatRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(i -> i.getArgument(0));
        when(venueSectionMapper.toResponse(savedSection, 15L)).thenReturn(expectedResponse);

        // When
        VenueSectionResponse result = sectionService.createSection(venueId, request);

        // Then
        assertThat(result.name()).isEqualTo("Orchestra");
        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.colCount()).isEqualTo(5);
        assertThat(result.activeSeatCount()).isEqualTo(15L); // 3 rows × 5 cols

        // Verify seat grid was generated
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Seat>> seatsCaptor = ArgumentCaptor.forClass(List.class);
        verify(seatRepository).saveAll(seatsCaptor.capture());
        List<Seat> generatedSeats = seatsCaptor.getValue();
        assertThat(generatedSeats).hasSize(15);

        // Verify row labels: A, B, C
        assertThat(generatedSeats.stream().filter(s -> "A".equals(s.getRowLabel())).count()).isEqualTo(5);
        assertThat(generatedSeats.stream().filter(s -> "B".equals(s.getRowLabel())).count()).isEqualTo(5);
        assertThat(generatedSeats.stream().filter(s -> "C".equals(s.getRowLabel())).count()).isEqualTo(5);

        // Verify outbox event
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("VenueSectionCreated");
    }

    @Test
    void shouldRejectDuplicateSectionName() {
        UUID venueId = UUID.randomUUID();
        Venue venue = Venue.builder().id(venueId).name("Test").build();
        CreateVenueSectionRequest request = new CreateVenueSectionRequest("Orchestra", 3, 5);

        when(venueRepository.findById(venueId)).thenReturn(Optional.of(venue));
        when(sectionRepository.existsByVenueIdAndName(venueId, "Orchestra")).thenReturn(true);

        assertThatThrownBy(() -> sectionService.createSection(venueId, request))
                .isInstanceOf(ConflictException.class);
        verify(seatRepository, never()).saveAll(anyList());
    }

    @Test
    void shouldRejectWhenExceedingVenueCapacity() {
        UUID venueId = UUID.randomUUID();
        Venue venue = Venue.builder().id(venueId).name("Test Venue").capacity(10).build();
        CreateVenueSectionRequest request = new CreateVenueSectionRequest("Orchestra", 3, 5); // 15 seats

        when(venueRepository.findById(venueId)).thenReturn(Optional.of(venue));
        when(sectionRepository.existsByVenueIdAndName(venueId, "Orchestra")).thenReturn(false);
        when(seatRepository.countActiveSeatsByVenueId(venueId)).thenReturn(0L);

        assertThatThrownBy(() -> sectionService.createSection(venueId, request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exceed venue capacity");
        verify(seatRepository, never()).saveAll(anyList());
    }

    @Test
    void shouldToggleSeatStatus() {
        // Given
        UUID venueId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        Venue venue = Venue.builder().id(venueId).build();
        VenueSection section = VenueSection.builder().id(sectionId).venue(venue).build();
        Seat seat = Seat.builder().id(seatId).rowLabel("A").seatNumber(1).gridX(0).gridY(0).isActive(true).build();
        UpdateSeatStatusRequest request = new UpdateSeatStatusRequest(false);

        when(venueRepository.existsById(venueId)).thenReturn(true);
        when(sectionRepository.findById(sectionId)).thenReturn(Optional.of(section));
        when(seatRepository.findByIdAndSectionId(seatId, sectionId)).thenReturn(Optional.of(seat));
        when(seatRepository.save(any(Seat.class))).thenReturn(seat);
        when(seatMapper.toResponse(seat)).thenReturn(
                new SeatResponse(seatId, "A", 1, 0, 0, false));

        // When
        SeatResponse result = sectionService.updateSeatStatus(venueId, sectionId, seatId, request);

        // Then
        assertThat(result.isActive()).isFalse();
    }

    @Test
    void shouldDeleteSectionSuccessfully() {
        UUID venueId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        Venue venue = Venue.builder().id(venueId).build();
        VenueSection section = VenueSection.builder().id(sectionId).venue(venue).name("Balcony").build();

        when(venueRepository.existsById(venueId)).thenReturn(true);
        when(sectionRepository.findById(sectionId)).thenReturn(Optional.of(section));

        sectionService.deleteSection(venueId, sectionId);

        verify(sectionRepository).delete(section);
    }

    @Test
    void shouldThrowNotFoundWhenDeletingMissingSection() {
        UUID venueId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();

        when(venueRepository.existsById(venueId)).thenReturn(true);
        when(sectionRepository.findById(sectionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sectionService.deleteSection(venueId, sectionId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
