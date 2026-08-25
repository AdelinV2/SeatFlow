# TASK-P02-005: Outbox Event Publisher & Comprehensive Test Suite

## 1. Task Metadata
- **Task ID:** `TASK-P02-005`
- **Git Branch:** `feat/p02-005-outbox-event-publisher-and-test-suite`
- **Target Module:** `backend/services/seat-map-service`
- **Phase:** `Phase 02 - Seat Map & Venue Service`
- **Related Specs:** `.ai/architecture/01-common-modules.md`, `.ai/architecture/05-messaging-and-outbox.md` (Section 3), `backend/AGENTS.md` (Section 7 & 10)
- **Related ADRs:** None
- **Dependencies:** `TASK-P02-004` (All production code must exist: entities, services, controllers, security)
- **Status:** `COMPLETED`

---

## 2. Objective & Invariants
Implement the `OutboxEventPublisher` scheduled worker that polls unpublished events from `outbox_events` and publishes them to Kafka via `KafkaTemplate`. Additionally, implement the complete test suite covering:
- **Unit tests** (Mockito): `VenueServiceImplTest`, `VenueSectionServiceImplTest`, `SeatMapLayoutServiceImplTest`, `OutboxEventPublisherTest`
- **Repository slice tests** (Testcontainers PostgreSQL): `VenueRepositoryTest`, `VenueSectionRepositoryTest`, `SeatRepositoryTest`, `OutboxEventRepositoryTest`
- **Controller slice tests** (`@WebMvcTest`): `VenueControllerTest`, `AdminVenueControllerTest`
- **Integration test** (`@SpringBootTest`): `SeatMapServiceIntegrationTest`

### Critical Invariants to Enforce:
- [ ] Outbox publisher uses `@Scheduled(fixedDelayString = ...)` — NEVER `@Scheduled(fixedRate = ...)`.
- [ ] Publisher sends events to the Kafka topic derived from `EventTopics` or a configurable topic property.
- [ ] Publisher uses aggregate ID as Kafka message key for partition ordering.
- [ ] Failed Kafka sends increment `retry_count` — publisher does NOT throw and stop on individual failures.
- [ ] Events exceeding `max_retries` (5) are skipped by the publisher (enforced by DB constraint).
- [ ] Repository tests use Testcontainers PostgreSQL 16-alpine with `@DataJpaTest`.
- [ ] Controller tests use `@WebMvcTest` with `spring-security-test` MockJwt.
- [ ] No `@RestControllerAdvice` in test configuration — `GlobalExceptionHandler` is auto-configured.
- [ ] Integration test validates end-to-end: create venue → create section (with auto-generated seats) → retrieve layout → verify outbox events.

---

## 3. Exact File Inventory

All paths relative to `backend/services/seat-map-service/`.

- `[NEW]` `src/main/java/com/seatflow/seatmap/messaging/producer/OutboxEventPublisher.java`
- `[NEW]` `src/test/java/com/seatflow/seatmap/messaging/producer/OutboxEventPublisherTest.java`
- `[NEW]` `src/test/java/com/seatflow/seatmap/service/VenueServiceImplTest.java`
- `[NEW]` `src/test/java/com/seatflow/seatmap/service/VenueSectionServiceImplTest.java`
- `[NEW]` `src/test/java/com/seatflow/seatmap/service/SeatMapLayoutServiceImplTest.java`
- `[NEW]` `src/test/java/com/seatflow/seatmap/repository/VenueRepositoryTest.java`
- `[NEW]` `src/test/java/com/seatflow/seatmap/repository/VenueSectionRepositoryTest.java`
- `[NEW]` `src/test/java/com/seatflow/seatmap/repository/SeatRepositoryTest.java`
- `[NEW]` `src/test/java/com/seatflow/seatmap/repository/OutboxEventRepositoryTest.java`
- `[NEW]` `src/test/java/com/seatflow/seatmap/web/controller/VenueControllerTest.java`
- `[NEW]` `src/test/java/com/seatflow/seatmap/web/controller/AdminVenueControllerTest.java`
- `[NEW]` `src/test/java/com/seatflow/seatmap/integration/SeatMapServiceIntegrationTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Outbox Event Publisher
```java
package com.seatflow.seatmap.messaging.producer;

import com.seatflow.common.events.EventTopics;
import com.seatflow.seatmap.model.entity.OutboxEvent;
import com.seatflow.seatmap.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxEventPublisher {

    private static final int MAX_RETRY_COUNT = 5;
    private static final int SEND_TIMEOUT_SECONDS = 30;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${outbox.publisher.topic:" + EventTopics.SEATMAP_EVENTS + "}")
    private String topic = EventTopics.SEATMAP_EVENTS;

    @Value("${outbox.publisher.batch-size:50}")
    private int batchSize = 50;

    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay-ms:3000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxEventRepository.findUnpublishedForUpdate(MAX_RETRY_COUNT, batchSize);

        if (events.isEmpty()) {
            return;
        }

        log.debug("Outbox publisher polling. unpublishedCount={}", events.size());

        for (OutboxEvent event : events) {
            try {
                CompletableFuture<SendResult<String, String>> sendFuture = kafkaTemplate.send(
                        topic,
                        event.getAggregateId().toString(),
                        event.getPayload()
                );
                sendFuture.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                int updated = outboxEventRepository.markPublished(event.getId(), Instant.now());
                if (updated == 0) {
                    log.debug("Outbox event already published (possibly by another instance). outboxEventId={}", event.getId());
                } else {
                    log.info("Outbox event published. outboxEventId={}, eventType={}, aggregateId={}, topic={}",
                            event.getId(), event.getEventType(), event.getAggregateId(), topic);
                }

            } catch (Exception ex) {
                int updated = outboxEventRepository.incrementRetryCount(event.getId(), MAX_RETRY_COUNT);
                if (updated == 0) {
                    log.error("Outbox event at max retry count or already published; parking. outboxEventId={}, eventType={}, aggregateId={}",
                            event.getId(), event.getEventType(), event.getAggregateId(), ex);
                } else {
                    log.warn("Failed to publish outbox event. outboxEventId={}, eventType={}, aggregateId={}, retryCount={}",
                            event.getId(), event.getEventType(), event.getAggregateId(), event.getRetryCount() + 1, ex);
                }
            }
        }
    }
}
```

### 4.2 Unit Test: `VenueServiceImplTest`
```java
package com.seatflow.seatmap.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.seatmap.mapper.VenueMapper;
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
```

### 4.3 Unit Test: `VenueSectionServiceImplTest`
```java
package com.seatflow.seatmap.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.seatmap.mapper.SeatMapper;
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
    void shouldVerifyRowLabelGeneration() {
        // Verify the alphabetic row label algorithm
        assertThat(VenueSectionServiceImpl.generateRowLabel(0)).isEqualTo("A");
        assertThat(VenueSectionServiceImpl.generateRowLabel(1)).isEqualTo("B");
        assertThat(VenueSectionServiceImpl.generateRowLabel(25)).isEqualTo("Z");
        assertThat(VenueSectionServiceImpl.generateRowLabel(26)).isEqualTo("AA");
        assertThat(VenueSectionServiceImpl.generateRowLabel(27)).isEqualTo("AB");
        assertThat(VenueSectionServiceImpl.generateRowLabel(51)).isEqualTo("AZ");
        assertThat(VenueSectionServiceImpl.generateRowLabel(52)).isEqualTo("BA");
    }
}
```

### 4.4 Unit Test: `SeatMapLayoutServiceImplTest`
```java
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
```

### 4.5 Unit Test: `OutboxEventPublisherTest`
```java
package com.seatflow.seatmap.messaging.producer;

import com.seatflow.common.events.EventTopics;
import com.seatflow.seatmap.model.entity.OutboxEvent;
import com.seatflow.seatmap.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTest {

    @InjectMocks
    private OutboxEventPublisher publisher;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(publisher, "batchSize", 50);
        ReflectionTestUtils.setField(publisher, "topic", EventTopics.SEATMAP_EVENTS);
    }

    @Test
    void shouldPublishPendingEventsAndMarkAsPublished() {
        // Given
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String payload = "{\"venueId\":\"" + aggregateId + "\"}";
        OutboxEvent event = OutboxEvent.builder()
                .id(eventId).aggregateId(aggregateId)
                .eventType("VenueCreated")
                .payload(payload)
                .retryCount(0)
                .build();

        when(outboxEventRepository.findUnpublishedForUpdate(5, 50))
                .thenReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> successfulFuture = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq(EventTopics.SEATMAP_EVENTS), eq(aggregateId.toString()), eq(payload)))
                .thenReturn(successfulFuture);
        when(outboxEventRepository.markPublished(eq(eventId), any(Instant.class)))
                .thenReturn(1);

        // When
        publisher.publishPendingEvents();

        // Then
        verify(kafkaTemplate).send(EventTopics.SEATMAP_EVENTS, aggregateId.toString(), payload);
        verify(outboxEventRepository).markPublished(eq(eventId), any(Instant.class));
        verify(outboxEventRepository, never()).incrementRetryCount(any(), anyInt());
    }

    @Test
    void shouldIncrementRetryCountOnKafkaFailure() {
        // Given
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String payload = "{\"venueId\":\"" + aggregateId + "\"}";
        OutboxEvent event = OutboxEvent.builder()
                .id(eventId).aggregateId(aggregateId)
                .eventType("VenueCreated").payload(payload)
                .retryCount(0).build();

        when(outboxEventRepository.findUnpublishedForUpdate(5, 50))
                .thenReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka unavailable"));
        when(kafkaTemplate.send(eq(EventTopics.SEATMAP_EVENTS), eq(aggregateId.toString()), eq(payload)))
                .thenReturn(failedFuture);
        when(outboxEventRepository.incrementRetryCount(eq(eventId), eq(5)))
                .thenReturn(1);

        // When
        publisher.publishPendingEvents();

        // Then
        verify(kafkaTemplate).send(EventTopics.SEATMAP_EVENTS, aggregateId.toString(), payload);
        verify(outboxEventRepository).incrementRetryCount(eventId, 5);
        verify(outboxEventRepository, never()).markPublished(any(), any());
    }

    @Test
    void shouldDoNothingWhenNoEventsAvailable() {
        when(outboxEventRepository.findUnpublishedForUpdate(5, 50))
                .thenReturn(Collections.emptyList());

        publisher.publishPendingEvents();

        verifyNoInteractions(kafkaTemplate);
        verify(outboxEventRepository, never()).markPublished(any(), any());
        verify(outboxEventRepository, never()).incrementRetryCount(any(), anyInt());
    }
}
```

### 4.6 Repository Slice Test: `VenueRepositoryTest`
```java
package com.seatflow.seatmap.repository;

import com.seatflow.seatmap.model.entity.Venue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class VenueRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_seatmap_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private VenueRepository venueRepository;

    @Test
    void shouldSaveAndFindVenueById() {
        Venue venue = Venue.builder()
                .name("Test Venue").address("123 Main St")
                .city("New York").country("USA").capacity(500)
                .build();

        Venue saved = venueRepository.save(venue);

        assertThat(venueRepository.findById(saved.getId())).isPresent();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getVersion()).isNotNull();
    }

    @Test
    void shouldCheckExistsByNameAndCity() {
        venueRepository.save(Venue.builder()
                .name("Grand Theatre").address("456 Broadway")
                .city("Boston").country("USA").capacity(300)
                .build());

        assertThat(venueRepository.existsByNameAndCity("Grand Theatre", "Boston")).isTrue();
        assertThat(venueRepository.existsByNameAndCity("Grand Theatre", "Chicago")).isFalse();
    }

    @Test
    void shouldFilterByCity() {
        venueRepository.save(Venue.builder().name("V1").address("A").city("NYC").country("USA").capacity(100).build());
        venueRepository.save(Venue.builder().name("V2").address("B").city("LA").country("USA").capacity(200).build());

        Page<Venue> result = venueRepository.findByFilters("NYC", null, PageRequest.of(0, 20));
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getCity()).isEqualTo("NYC");
    }

    @Test
    void shouldSearchByName() {
        venueRepository.save(Venue.builder().name("Grand Opera House").address("A").city("NYC").country("USA").capacity(100).build());
        venueRepository.save(Venue.builder().name("City Arena").address("B").city("NYC").country("USA").capacity(200).build());

        Page<Venue> result = venueRepository.findByFilters(null, "Grand", PageRequest.of(0, 20));
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getName()).contains("Grand");
    }
}
```

### 4.7 Repository Slice Test: `VenueSectionRepositoryTest`
```java
package com.seatflow.seatmap.repository;

import com.seatflow.seatmap.model.entity.Venue;
import com.seatflow.seatmap.model.entity.VenueSection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class VenueSectionRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_seatmap_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private VenueSectionRepository sectionRepository;

    private Venue testVenue;

    @BeforeEach
    void setUp() {
        sectionRepository.deleteAll();
        venueRepository.deleteAll();

        testVenue = venueRepository.save(Venue.builder()
                .name("Section Test Venue").address("100 Main St")
                .city("Chicago").country("USA").capacity(500)
                .build());
    }

    @Test
    void shouldFindByVenueIdOrderByNameAsc() {
        sectionRepository.save(VenueSection.builder().venue(testVenue).name("Orchestra").rowCount(10).colCount(20).build());
        sectionRepository.save(VenueSection.builder().venue(testVenue).name("Balcony").rowCount(5).colCount(15).build());

        List<VenueSection> sections = sectionRepository.findByVenueIdOrderByNameAsc(testVenue.getId());

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).getName()).isEqualTo("Balcony");
        assertThat(sections.get(1).getName()).isEqualTo("Orchestra");
    }

    @Test
    void shouldCheckExistsByVenueIdAndName() {
        sectionRepository.save(VenueSection.builder().venue(testVenue).name("VIP Lounge").rowCount(2).colCount(5).build());

        assertThat(sectionRepository.existsByVenueIdAndName(testVenue.getId(), "VIP Lounge")).isTrue();
        assertThat(sectionRepository.existsByVenueIdAndName(testVenue.getId(), "General")).isFalse();
    }
}
```

### 4.8 Repository Slice Test: `SeatRepositoryTest`
```java
package com.seatflow.seatmap.repository;

import com.seatflow.seatmap.model.entity.Seat;
import com.seatflow.seatmap.model.entity.Venue;
import com.seatflow.seatmap.model.entity.VenueSection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class SeatRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_seatmap_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private VenueSectionRepository sectionRepository;

    @Autowired
    private SeatRepository seatRepository;

    private VenueSection testSection;

    @BeforeEach
    void setUp() {
        seatRepository.deleteAll();
        sectionRepository.deleteAll();
        venueRepository.deleteAll();

        Venue venue = venueRepository.save(Venue.builder()
                .name("Test Venue").address("123 St").city("NYC").country("USA").capacity(100).build());
        testSection = sectionRepository.save(VenueSection.builder()
                .venue(venue).name("Section A").rowCount(2).colCount(3).build());
    }

    @Test
    void shouldFindActiveSeatsBySectionId() {
        // Create active and inactive seats
        seatRepository.save(Seat.builder().section(testSection).rowLabel("A").seatNumber(1).gridX(0).gridY(0).isActive(true).build());
        seatRepository.save(Seat.builder().section(testSection).rowLabel("A").seatNumber(2).gridX(1).gridY(0).isActive(true).build());
        seatRepository.save(Seat.builder().section(testSection).rowLabel("A").seatNumber(3).gridX(2).gridY(0).isActive(false).build());

        List<Seat> activeSeats = seatRepository.findActiveSeatsBySectionId(testSection.getId());
        assertThat(activeSeats).hasSize(2);
        assertThat(activeSeats).allMatch(Seat::getIsActive);
    }

    @Test
    void shouldCountActiveSeatsBySection() {
        seatRepository.save(Seat.builder().section(testSection).rowLabel("A").seatNumber(1).gridX(0).gridY(0).isActive(true).build());
        seatRepository.save(Seat.builder().section(testSection).rowLabel("A").seatNumber(2).gridX(1).gridY(0).isActive(false).build());

        long count = seatRepository.countBySectionIdAndIsActiveTrue(testSection.getId());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void shouldFindSeatByIdAndSectionId() {
        Seat seat = seatRepository.save(Seat.builder()
                .section(testSection).rowLabel("B").seatNumber(1).gridX(0).gridY(1).isActive(true).build());

        assertThat(seatRepository.findByIdAndSectionId(seat.getId(), testSection.getId())).isPresent();
        assertThat(seatRepository.findByIdAndSectionId(seat.getId(), java.util.UUID.randomUUID())).isEmpty();
    }

    @Test
    void shouldCountActiveSeatsByVenueId() {
        seatRepository.save(Seat.builder().section(testSection).rowLabel("A").seatNumber(1).gridX(0).gridY(0).isActive(true).build());
        seatRepository.save(Seat.builder().section(testSection).rowLabel("A").seatNumber(2).gridX(1).gridY(0).isActive(true).build());
        seatRepository.save(Seat.builder().section(testSection).rowLabel("A").seatNumber(3).gridX(2).gridY(0).isActive(false).build());

        long count = seatRepository.countActiveSeatsByVenueId(testSection.getVenue().getId());
        assertThat(count).isEqualTo(2);
    }
}
```

### 4.9 Repository Slice Test: `OutboxEventRepositoryTest`
```java
package com.seatflow.seatmap.repository;

import com.seatflow.seatmap.model.entity.OutboxEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class OutboxEventRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_seatmap_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    void shouldFindUnpublishedEventsOrderedByCreatedAt() {
        OutboxEvent event1 = OutboxEvent.builder().aggregateId(UUID.randomUUID())
                .eventType("VenueCreated").payload("{\"test\":\"1\"}").build();
        OutboxEvent event2 = OutboxEvent.builder().aggregateId(UUID.randomUUID())
                .eventType("VenueSectionCreated").payload("{\"test\":\"2\"}").build();
        OutboxEvent publishedEvent = OutboxEvent.builder().aggregateId(UUID.randomUUID())
                .eventType("VenueCreated").payload("{\"test\":\"published\"}")
                .publishedAt(Instant.now()).build();

        outboxEventRepository.saveAll(List.of(event1, event2, publishedEvent));

        List<OutboxEvent> unpublished = outboxEventRepository.findUnpublishedForUpdate(5, 50);
        assertThat(unpublished).hasSize(2);
        assertThat(unpublished).noneMatch(e -> e.getPublishedAt() != null);
    }

    @Test
    void shouldReturnEmptyWhenAllEventsPublished() {
        OutboxEvent event = OutboxEvent.builder().aggregateId(UUID.randomUUID())
                .eventType("VenueCreated").payload("{\"test\":\"done\"}")
                .publishedAt(Instant.now()).build();
        outboxEventRepository.save(event);

        List<OutboxEvent> unpublished = outboxEventRepository.findUnpublishedForUpdate(5, 50);
        assertThat(unpublished).isEmpty();
    }

    @Test
    void shouldMarkEventAsPublished() {
        OutboxEvent event = outboxEventRepository.save(OutboxEvent.builder().aggregateId(UUID.randomUUID())
                .eventType("VenueCreated").payload("{\"test\":\"mark\"}").build());

        int updated = outboxEventRepository.markPublished(event.getId(), Instant.now());
        assertThat(updated).isEqualTo(1);

        OutboxEvent reloaded = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getPublishedAt()).isNotNull();
    }

    @Test
    void shouldIncrementRetryCount() {
        OutboxEvent event = outboxEventRepository.save(OutboxEvent.builder().aggregateId(UUID.randomUUID())
                .eventType("VenueCreated").payload("{\"test\":\"retry\"}").retryCount(0).build());

        int updated = outboxEventRepository.incrementRetryCount(event.getId(), 5);
        assertThat(updated).isEqualTo(1);

        OutboxEvent reloaded = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getRetryCount()).isEqualTo(1);
    }
}
```

### 4.10 Controller Slice Test: `VenueControllerTest`
```java
package com.seatflow.seatmap.web.controller;

import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.security.converter.JwtRoleConverter;
import com.seatflow.seatmap.config.SecurityConfig;
import com.seatflow.seatmap.service.SeatMapLayoutService;
import com.seatflow.seatmap.service.VenueService;
import com.seatflow.seatmap.web.dto.response.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(VenueController.class)
@Import(SecurityConfig.class)
class VenueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VenueService venueService;

    @MockitoBean
    private SeatMapLayoutService seatMapLayoutService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private JwtRoleConverter jwtRoleConverter;

    @Test
    void shouldListVenuesWithoutAuthentication() throws Exception {
        UUID venueId = UUID.randomUUID();
        VenueResponse response = new VenueResponse(venueId, "Theatre", "123 St", "NYC", "USA", 500, Instant.now());
        PagedResult<VenueResponse> result = PagedResult.of(List.of(response), 0, 20, 1);

        when(venueService.listVenues(any(), any(), any())).thenReturn(result);

        mockMvc.perform(get("/api/venues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Theatre"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void shouldGetVenueDetailWithoutAuthentication() throws Exception {
        UUID venueId = UUID.randomUUID();
        VenueDetailResponse response = new VenueDetailResponse(venueId, "Theatre", "123 St",
                "NYC", "USA", 500, List.of(), Instant.now());

        when(venueService.getVenueById(venueId)).thenReturn(response);

        mockMvc.perform(get("/api/venues/{venueId}", venueId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Theatre"));
    }

    @Test
    void shouldGetVenueLayoutWithoutAuthentication() throws Exception {
        UUID venueId = UUID.randomUUID();
        VenueSeatMapLayoutResponse response = new VenueSeatMapLayoutResponse(venueId, "Theatre", 500, List.of());

        when(seatMapLayoutService.getVenueLayout(venueId)).thenReturn(response);

        mockMvc.perform(get("/api/venues/{venueId}/layout", venueId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venueId").value(venueId.toString()))
                .andExpect(jsonPath("$.name").value("Theatre"));
    }
}
```

### 4.11 Controller Slice Test: `AdminVenueControllerTest`
```java
package com.seatflow.seatmap.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.security.SecurityRoles;
import com.seatflow.common.security.converter.JwtRoleConverter;
import com.seatflow.seatmap.config.SecurityConfig;
import com.seatflow.seatmap.service.VenueSectionService;
import com.seatflow.seatmap.service.VenueService;
import com.seatflow.seatmap.web.dto.request.CreateVenueRequest;
import com.seatflow.seatmap.web.dto.request.CreateVenueSectionRequest;
import com.seatflow.seatmap.web.dto.response.VenueResponse;
import com.seatflow.seatmap.web.dto.response.VenueSectionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminVenueController.class)
@Import(SecurityConfig.class)
class AdminVenueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private VenueService venueService;

    @MockitoBean
    private VenueSectionService sectionService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private JwtRoleConverter jwtRoleConverter;

    @Test
    void shouldCreateVenueForAdmin() throws Exception {
        UUID venueId = UUID.randomUUID();
        CreateVenueRequest request = new CreateVenueRequest("Grand Theatre", "123 Main St", "NYC", "USA", 500);
        VenueResponse response = new VenueResponse(venueId, "Grand Theatre", "123 Main St",
                "NYC", "USA", 500, Instant.now());

        when(venueService.createVenue(any(CreateVenueRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/admin/venues")
                        .with(user("admin").roles(SecurityRoles.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Grand Theatre"))
                .andExpect(jsonPath("$.capacity").value(500));
    }

    @Test
    void shouldReject403ForNonAdminUser() throws Exception {
        CreateVenueRequest request = new CreateVenueRequest("Theatre", "123 St", "NYC", "USA", 100);

        mockMvc.perform(post("/api/admin/venues")
                        .with(user("customer").roles(SecurityRoles.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReject401WhenUnauthenticated() throws Exception {
        CreateVenueRequest request = new CreateVenueRequest("Theatre", "123 St", "NYC", "USA", 100);

        mockMvc.perform(post("/api/admin/venues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldCreateSectionWithSeatGrid() throws Exception {
        UUID venueId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        CreateVenueSectionRequest request = new CreateVenueSectionRequest("Orchestra", 5, 10);
        VenueSectionResponse response = new VenueSectionResponse(sectionId, "Orchestra", 5, 10, 50L);

        when(sectionService.createSection(eq(venueId), any(CreateVenueSectionRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/admin/venues/{venueId}/sections", venueId)
                        .with(user("admin").roles(SecurityRoles.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Orchestra"))
                .andExpect(jsonPath("$.activeSeatCount").value(50));
    }
}
```

### 4.12 Integration Test: `SeatMapServiceIntegrationTest`
```java
package com.seatflow.seatmap.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.seatmap.model.entity.OutboxEvent;
import com.seatflow.seatmap.repository.OutboxEventRepository;
import com.seatflow.seatmap.repository.SeatRepository;
import com.seatflow.seatmap.repository.VenueRepository;
import com.seatflow.seatmap.repository.VenueSectionRepository;
import com.seatflow.seatmap.web.dto.request.CreateVenueRequest;
import com.seatflow.seatmap.web.dto.request.CreateVenueSectionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class SeatMapServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_seatmap_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("outbox.publisher.fixed-delay-ms", () -> "60000");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private VenueSectionRepository sectionRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        seatRepository.deleteAll();
        sectionRepository.deleteAll();
        venueRepository.deleteAll();
    }

    @Test
    void shouldCreateVenueAndVerifyOutboxEvent() throws Exception {
        CreateVenueRequest request = new CreateVenueRequest("Integration Theatre", "999 Test Ave", "NYC", "USA", 500);

        MvcResult result = mockMvc.perform(post("/api/admin/venues")
                        .with(jwt().jwt(j -> j
                                .subject("admin-ext")
                                .claim("email", "admin@test.com")
                                .claim("roles", List.of("ROLE_ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Integration Theatre"))
                .andExpect(jsonPath("$.capacity").value(500))
                .andReturn();

        // Verify database
        assertThat(venueRepository.existsByNameAndCity("Integration Theatre", "NYC")).isTrue();

        // Verify outbox event
        List<OutboxEvent> outboxEvents = outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        assertThat(outboxEvents).hasSize(1);
        assertThat(outboxEvents.getFirst().getEventType()).isEqualTo("VenueCreated");
    }

    @Test
    void shouldCreateSectionWithAutoGeneratedSeats() throws Exception {
        // 1. Create venue first
        CreateVenueRequest venueRequest = new CreateVenueRequest("Seat Grid Venue", "100 Grid Ave", "LA", "USA", 1000);
        MvcResult venueResult = mockMvc.perform(post("/api/admin/venues")
                        .with(jwt().jwt(j -> j
                                .subject("admin-ext")
                                .claim("email", "admin@test.com")
                                .claim("roles", List.of("ROLE_ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(venueRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String venueId = objectMapper.readTree(venueResult.getResponse().getContentAsString()).get("id").asText();

        // 2. Create section with 3 rows × 5 seats
        CreateVenueSectionRequest sectionRequest = new CreateVenueSectionRequest("Orchestra", 3, 5);
        mockMvc.perform(post("/api/admin/venues/{venueId}/sections", venueId)
                        .with(jwt().jwt(j -> j
                                .subject("admin-ext")
                                .claim("email", "admin@test.com")
                                .claim("roles", List.of("ROLE_ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sectionRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Orchestra"))
                .andExpect(jsonPath("$.activeSeatCount").value(15));

        // 3. Verify seats in database: 3 × 5 = 15 seats
        assertThat(seatRepository.findActiveSeatsForVenueLayout(UUID.fromString(venueId))).hasSize(15);

        // 4. Verify outbox: VenueCreated + VenueSectionCreated
        List<OutboxEvent> events = outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        assertThat(events).hasSize(2);
        assertThat(events.stream().map(OutboxEvent::getEventType).toList())
                .containsExactlyInAnyOrder("VenueCreated", "VenueSectionCreated");
    }

    @Test
    void shouldRetrieveVenueLayoutPublicly() throws Exception {
        // Setup: create venue + section + seats
        CreateVenueRequest venueRequest = new CreateVenueRequest("Layout Venue", "200 Layout St", "CHI", "USA", 200);
        MvcResult venueResult = mockMvc.perform(post("/api/admin/venues")
                        .with(jwt().jwt(j -> j
                                .subject("admin-ext")
                                .claim("email", "admin@test.com")
                                .claim("roles", List.of("ROLE_ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(venueRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String venueId = objectMapper.readTree(venueResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/admin/venues/{venueId}/sections", venueId)
                        .with(jwt().jwt(j -> j.subject("admin-ext").claim("email", "admin@test.com").claim("roles", List.of("ROLE_ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateVenueSectionRequest("Balcony", 2, 4))))
                .andExpect(status().isCreated());

        // Public layout retrieval — NO authentication required
        mockMvc.perform(get("/api/venues/{venueId}/layout", venueId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venueId").value(venueId))
                .andExpect(jsonPath("$.name").value("Layout Venue"))
                .andExpect(jsonPath("$.sections").isArray())
                .andExpect(jsonPath("$.sections", hasSize(1)))
                .andExpect(jsonPath("$.sections[0].name").value("Balcony"))
                .andExpect(jsonPath("$.sections[0].seats", hasSize(8))); // 2 × 4 = 8 seats
    }

    @Test
    void shouldListVenuesPubliclyWithPagination() throws Exception {
        // Create two venues
        mockMvc.perform(post("/api/admin/venues")
                        .with(jwt().jwt(j -> j.subject("admin-ext").claim("email", "admin@test.com").claim("roles", List.of("ROLE_ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateVenueRequest("V1", "A", "NYC", "USA", 100))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/admin/venues")
                        .with(jwt().jwt(j -> j.subject("admin-ext").claim("email", "admin@test.com").claim("roles", List.of("ROLE_ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateVenueRequest("V2", "B", "LA", "USA", 200))))
                .andExpect(status().isCreated());

        // Public listing — no auth
        mockMvc.perform(get("/api/venues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void shouldRejectUnauthenticatedAdminAccess() throws Exception {
        mockMvc.perform(post("/api/admin/venues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateVenueRequest("T", "A", "NYC", "USA", 100))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectNonAdminAccessToAdminEndpoints() throws Exception {
        mockMvc.perform(post("/api/admin/venues")
                        .with(jwt().jwt(j -> j.subject("user-ext").claim("email", "user@test.com").claim("roles", List.of("ROLE_CUSTOMER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateVenueRequest("T", "A", "NYC", "USA", 100))))
                .andExpect(status().isForbidden());
    }
}
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Step 1 — Branch Checkout:** `git checkout -b feat/p02-005-outbox-event-publisher-and-test-suite develop`
2. **Step 2 — OutboxEventPublisher:** Create the scheduled component with `@Scheduled(fixedDelayString = ...)`, `KafkaTemplate`, and retry/error handling.
3. **Step 3 — VenueServiceImplTest:** Create unit tests with Mockito for venue creation (with outbox), duplicate rejection, partial update, and pagination.
4. **Step 4 — VenueSectionServiceImplTest:** Create unit tests for section creation with seat grid generation, capacity rejection, duplicate rejection, seat status toggle, and row label algorithm verification.
5. **Step 5 — SeatMapLayoutServiceImplTest:** Create unit tests for venue layout retrieval and 404 handling.
6. **Step 6 — OutboxEventPublisherTest:** Create unit tests for successful publishing, retry on failure, and empty queue handling.
7. **Step 7 — VenueRepositoryTest:** Create repository slice test with Testcontainers for save, find, duplicate check, and filtering.
8. **Step 8 — VenueSectionRepositoryTest:** Create repository slice test with Testcontainers for section ordering and duplicate check.
9. **Step 9 — SeatRepositoryTest:** Create repository slice test for active seat queries, count, and find-by-id-and-section.
10. **Step 10 — OutboxEventRepositoryTest:** Create repository slice test for unpublished event polling, row locking, and atomic updates.
11. **Step 11 — VenueControllerTest:** Create `@WebMvcTest` validating public endpoints (no auth required).
12. **Step 12 — AdminVenueControllerTest:** Create `@WebMvcTest` with MockMvc validating RBAC (admin allowed, customer rejected, unauthenticated rejected).
13. **Step 13 — SeatMapServiceIntegrationTest:** Create `@SpringBootTest` end-to-end test with Testcontainers verifying venue creation, section creation with auto-generated seats, layout retrieval, outbox events, and security enforcement.
14. **Step 14 — Run Full Test Suite:** Execute verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the `backend/` directory:
```bash
mvn clean test -pl services/seat-map-service -am
```
- [ ] All unit tests pass (Mockito): `VenueServiceImplTest`, `VenueSectionServiceImplTest`, `SeatMapLayoutServiceImplTest`, `OutboxEventPublisherTest`.
- [ ] All repository slice tests pass (Testcontainers): `VenueRepositoryTest`, `VenueSectionRepositoryTest`, `SeatRepositoryTest`, `OutboxEventRepositoryTest`.
- [ ] All controller slice tests pass (`@WebMvcTest`): `VenueControllerTest`, `AdminVenueControllerTest`.
- [ ] Integration tests pass (`@SpringBootTest`): `SeatMapServiceIntegrationTest`.
- [ ] `OutboxEventPublisher` compiles and uses configurable topic (`outbox.publisher.topic`).
- [ ] No `@RestControllerAdvice` created in this module.
- [ ] Row label generation verified: A-Z then AA, AB, etc.
- [ ] Task file is moved to `.ai/tasks/completed/phase-02-seat-map-service/005-outbox-event-publisher-and-test-suite.md`.
