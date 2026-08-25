package com.seatflow.seatmap.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.seatmap.mapper.VenueMapper;
import com.seatflow.seatmap.mapper.VenueSectionMapper;
import com.seatflow.seatmap.model.entity.OutboxEvent;
import com.seatflow.seatmap.model.entity.Venue;
import com.seatflow.seatmap.model.entity.VenueSection;
import com.seatflow.seatmap.repository.OutboxEventRepository;
import com.seatflow.seatmap.repository.SeatRepository;
import com.seatflow.seatmap.repository.VenueRepository;
import com.seatflow.seatmap.repository.VenueSectionRepository;
import com.seatflow.seatmap.service.impl.VenueServiceImpl;
import com.seatflow.seatmap.web.dto.request.CreateVenueRequest;
import com.seatflow.seatmap.web.dto.request.UpdateVenueRequest;
import com.seatflow.seatmap.web.dto.response.VenueDetailResponse;
import com.seatflow.seatmap.web.dto.response.VenueResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VenueServiceImplTest {

    @InjectMocks
    private VenueServiceImpl venueService;

    @Mock
    private VenueRepository venueRepository;

    @Mock
    private VenueSectionRepository sectionRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private VenueMapper venueMapper;

    @Mock
    private VenueSectionMapper venueSectionMapper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldCreateVenueAndWriteOutboxEvent() {
        // Given
        CreateVenueRequest request = new CreateVenueRequest("Grand Theatre", "123 Main St", "New York", "USA", 500);
        UUID venueId = UUID.randomUUID();
        Venue savedVenue = Venue.builder().id(venueId).name("Grand Theatre").address("123 Main St")
                .city("New York").country("USA").capacity(500).createdAt(Instant.now()).updatedAt(Instant.now()).build();
        VenueResponse expectedResponse = new VenueResponse(venueId, "Grand Theatre", "123 Main St",
                "New York", "USA", 500, savedVenue.getCreatedAt());

        when(venueRepository.existsByNameAndCity("Grand Theatre", "New York")).thenReturn(false);
        when(venueRepository.save(any(Venue.class))).thenReturn(savedVenue);
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(i -> i.getArgument(0));
        when(venueMapper.toResponse(savedVenue)).thenReturn(expectedResponse);

        // When
        VenueResponse result = venueService.createVenue(request);

        // Then
        assertThat(result).isEqualTo(expectedResponse);
        verify(venueRepository).save(any(Venue.class));

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        OutboxEvent capturedEvent = outboxCaptor.getValue();
        assertThat(capturedEvent.getEventType()).isEqualTo("VenueCreated");
        assertThat(capturedEvent.getAggregateId()).isEqualTo(venueId);
        assertThat(capturedEvent.getPayload()).contains("Grand Theatre");
    }

    @Test
    void shouldRejectDuplicateVenueNameInSameCity() {
        // Given
        CreateVenueRequest request = new CreateVenueRequest("Grand Theatre", "123 Main St", "New York", "USA", 500);
        when(venueRepository.existsByNameAndCity("Grand Theatre", "New York")).thenReturn(true);

        // Then
        assertThatThrownBy(() -> venueService.createVenue(request))
                .isInstanceOf(ConflictException.class);
        verify(venueRepository, never()).save(any(Venue.class));
    }

    @Test
    void shouldUpdateVenuePartially() {
        // Given
        UUID venueId = UUID.randomUUID();
        Venue existingVenue = Venue.builder().id(venueId).name("Old Name").address("Old Address")
                .city("Boston").country("USA").capacity(300).createdAt(Instant.now()).updatedAt(Instant.now()).build();
        UpdateVenueRequest request = new UpdateVenueRequest("New Name", null, null, null, 600);

        when(venueRepository.findById(venueId)).thenReturn(Optional.of(existingVenue));
        when(venueRepository.save(any(Venue.class))).thenReturn(existingVenue);
        when(venueMapper.toResponse(existingVenue)).thenReturn(
                new VenueResponse(venueId, "New Name", "Old Address", "Boston", "USA", 600, existingVenue.getCreatedAt()));

        // When
        VenueResponse result = venueService.updateVenue(venueId, request);

        // Then
        assertThat(result.name()).isEqualTo("New Name");
        assertThat(result.capacity()).isEqualTo(600);
    }

    @Test
    void shouldThrowNotFoundWhenUpdatingNonExistentVenue() {
        UUID venueId = UUID.randomUUID();
        UpdateVenueRequest request = new UpdateVenueRequest("Name", null, null, null, null);
        when(venueRepository.findById(venueId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> venueService.updateVenue(venueId, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void shouldReturnPagedVenues() {
        // Given
        Pageable pageable = PageRequest.of(0, 20);
        Venue venue1 = Venue.builder().id(UUID.randomUUID()).name("Venue A").address("123").city("NYC")
                .country("USA").capacity(100).createdAt(Instant.now()).updatedAt(Instant.now()).build();
        var page = new PageImpl<>(List.of(venue1), pageable, 1);
        when(venueRepository.findByFilters(null, null, pageable)).thenReturn(page);
        when(venueMapper.toResponse(any(Venue.class))).thenReturn(
                new VenueResponse(venue1.getId(), "Venue A", "123", "NYC", "USA", 100, venue1.getCreatedAt()));

        // When
        PagedResult<VenueResponse> result = venueService.listVenues(null, null, pageable);

        // Then
        assertThat(result.content()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(1);
    }
}
