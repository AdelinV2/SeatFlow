package com.seatflow.seatmap.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.seatmap.model.entity.OutboxEvent;
import com.seatflow.seatmap.model.entity.Seat;
import com.seatflow.seatmap.model.entity.Venue;
import com.seatflow.seatmap.model.entity.VenueLayoutElement;
import com.seatflow.seatmap.model.entity.VenueSection;
import com.seatflow.seatmap.model.enums.LayoutElementType;
import com.seatflow.seatmap.repository.OutboxEventRepository;
import com.seatflow.seatmap.repository.SeatRepository;
import com.seatflow.seatmap.repository.VenueLayoutElementRepository;
import com.seatflow.seatmap.repository.VenueRepository;
import com.seatflow.seatmap.repository.VenueSectionRepository;
import com.seatflow.seatmap.service.VenueLayoutService;
import com.seatflow.seatmap.service.SeatMapLayoutService;
import com.seatflow.seatmap.web.dto.request.CreateVenueRequest;
import com.seatflow.seatmap.web.dto.request.CreateVenueSectionRequest;
import com.seatflow.seatmap.web.dto.request.SaveVenueLayoutRequest;
import com.seatflow.seatmap.web.dto.request.UpdateSeatStatusRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Autowired
    private VenueLayoutElementRepository elementRepository;

    @Autowired
    private VenueLayoutService layoutService;

    @Autowired
    private SeatMapLayoutService publicLayoutService;

    @Autowired
    private LayoutReadBarrier layoutReadBarrier;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @TestConfiguration
    @EnableAspectJAutoProxy
    static class LayoutReadBarrierConfiguration {
        @Bean
        LayoutReadBarrier layoutReadBarrier() {
            return new LayoutReadBarrier();
        }
    }

    @Aspect
    static class LayoutReadBarrier {
        private final AtomicReference<Thread> targetThread = new AtomicReference<>();
        private final AtomicReference<CountDownLatch> reached = new AtomicReference<>();
        private final AtomicReference<CountDownLatch> released = new AtomicReference<>();

        void arm(Thread thread, CountDownLatch reachedLatch, CountDownLatch releasedLatch) {
            targetThread.set(thread);
            reached.set(reachedLatch);
            released.set(releasedLatch);
        }

        void disarm() {
            targetThread.set(null);
            reached.set(null);
            released.set(null);
        }

        @Around("execution(* com.seatflow.seatmap.repository.VenueSectionRepository.findByVenueId*OrderByZIndexAscNameAsc(..))")
        Object pauseBetweenVenueAndChildren(ProceedingJoinPoint joinPoint) throws Throwable {
            if (Thread.currentThread() == targetThread.get()) {
                CountDownLatch reachedLatch = reached.get();
                CountDownLatch releasedLatch = released.get();
                reachedLatch.countDown();
                if (!releasedLatch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("layout read barrier timeout");
                }
            }
            return joinPoint.proceed();
        }
    }

    @BeforeEach
    void cleanUp() {
        layoutReadBarrier.disarm();
        outboxEventRepository.deleteAll();
        elementRepository.deleteAll();
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
                                .claim("roles", List.of("ROLE_ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
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
                                .claim("roles", List.of("ROLE_ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
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
                                .claim("roles", List.of("ROLE_ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
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
                                .claim("roles", List.of("ROLE_ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(venueRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String venueId = objectMapper.readTree(venueResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/admin/venues/{venueId}/sections", venueId)
                        .with(jwt().jwt(j -> j.subject("admin-ext").claim("email", "admin@test.com").claim("roles", List.of("ROLE_ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
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
                        .with(jwt().jwt(j -> j.subject("admin-ext").claim("email", "admin@test.com").claim("roles", List.of("ROLE_ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateVenueRequest("V1", "A", "NYC", "USA", 100))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/admin/venues")
                        .with(jwt().jwt(j -> j.subject("admin-ext").claim("email", "admin@test.com").claim("roles", List.of("ROLE_ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
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
                        .with(jwt().jwt(j -> j.subject("user-ext").claim("email", "user@test.com").claim("roles", List.of("ROLE_CUSTOMER")))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateVenueRequest("T", "A", "NYC", "USA", 100))))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // TASK-P11-004: atomic versioned layout APIs
    // ------------------------------------------------------------------

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j.subject("admin-ext").claim("email", "admin@test.com")
                        .claim("roles", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private Venue seedVenue(String name, String city, int capacity) {
        return venueRepository.saveAndFlush(Venue.builder()
                .name(name).address("1 Test Ave").city(city).country("USA").capacity(capacity)
                .build());
    }

    private VenueSection seedSection(Venue venue, String name) {
        return sectionRepository.saveAndFlush(VenueSection.builder()
                .venue(venue).name(name).rowCount(2).colCount(2).isActive(true)
                .positionX(new java.math.BigDecimal("0.000"))
                .positionY(new java.math.BigDecimal("0.000"))
                .width(new java.math.BigDecimal("88.000"))
                .height(new java.math.BigDecimal("88.000"))
                .rotationDeg(new java.math.BigDecimal("0.000")).zIndex(0)
                .build());
    }

    private Seat seedSeat(VenueSection section, String row, int number, int gridX, int gridY, boolean active) {
        return seatRepository.saveAndFlush(Seat.builder()
                .section(section).rowLabel(row).seatNumber(number)
                .gridX(gridX).gridY(gridY).isActive(active)
                .positionX(new java.math.BigDecimal(gridX * 44L + ".000"))
                .positionY(new java.math.BigDecimal(gridY * 44L + ".000"))
                .build());
    }

    private SaveVenueLayoutRequest.SeatUpsert seatUpsert(UUID seatId, String row, int number, boolean active) {
        int rowIdx = row.charAt(0) - 'A';
        int colIdx = number - 1;
        return new SaveVenueLayoutRequest.SeatUpsert(seatId, row, number, colIdx, rowIdx,
                new java.math.BigDecimal(colIdx * 44L + ".000"),
                new java.math.BigDecimal(rowIdx * 44L + ".000"), active);
    }

    private SaveVenueLayoutRequest.SeatUpsert seatUpsertAt(
            UUID seatId, String row, int number, int gridX, int gridY,
            String positionX, String positionY, boolean active) {
        return new SaveVenueLayoutRequest.SeatUpsert(
                seatId, row, number, gridX, gridY,
                new java.math.BigDecimal(positionX), new java.math.BigDecimal(positionY), active);
    }

    private SaveVenueLayoutRequest.SectionUpsert sectionUpsert(UUID sectionId, String name,
                                                               List<SaveVenueLayoutRequest.SeatUpsert> seats) {
        return new SaveVenueLayoutRequest.SectionUpsert(sectionId, name, 2, 2, true,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                new java.math.BigDecimal("88"), new java.math.BigDecimal("88"),
                java.math.BigDecimal.ZERO, 0, null, seats);
    }

    private SaveVenueLayoutRequest.SectionUpsert inactiveSectionUpsert(String name) {
        return new SaveVenueLayoutRequest.SectionUpsert(null, name, 1, 1, false,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                new java.math.BigDecimal("44"), new java.math.BigDecimal("44"),
                java.math.BigDecimal.ZERO, 0, null, List.of());
    }

    @Test
    void shouldSaveLayoutAtomicallyAndBumpVersionOnce() throws Exception {
        Venue venue = seedVenue("Atomic Hall", "NYC", 100);
        VenueSection section = seedSection(venue, "Orchestra");
        Seat seat = seedSeat(section, "A", 1, 0, 0, true);
        UUID venueId = venue.getId();

        SaveVenueLayoutRequest request = new SaveVenueLayoutRequest(0L,
                List.of(sectionUpsert(section.getId(), "Renamed",
                        List.of(seatUpsert(seat.getId(), "A", 1, true),
                                seatUpsert(null, "A", 2, true)))),
                List.of());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/admin/venues/{venueId}/layout", venueId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layoutVersion").value(1))
                .andExpect(jsonPath("$.sections[0].name").value("Renamed"));

        assertThat(venueRepository.findById(venueId).orElseThrow().getLayoutVersion()).isEqualTo(1L);
        // Stable UUID survived rename + new seat creation.
        assertThat(seatRepository.findById(seat.getId())).isPresent();
    }

    @Test
    void shouldReturn409WithStableCodeOnStaleVersion() throws Exception {
        Venue venue = seedVenue("Stale Hall", "LA", 100);
        VenueSection section = seedSection(venue, "Floor");
        seedSeat(section, "A", 1, 0, 0, true);
        UUID venueId = venue.getId();

        SaveVenueLayoutRequest first = new SaveVenueLayoutRequest(0L,
                List.of(sectionUpsert(section.getId(), "Floor",
                        List.of(seatUpsert(null, "A", 2, true)))),
                List.of());
        // First save bumps to 1 via service (avoids MockMvc JSON for setup).
        layoutService.saveLayout(venueId, first);
        assertThat(venueRepository.findById(venueId).orElseThrow().getLayoutVersion()).isEqualTo(1L);

        // Stale retry with version 0 must return 409 SF_409_CONFLICT, not create duplicates.
        long seatsBefore = seatRepository.count();
        SaveVenueLayoutRequest stale = new SaveVenueLayoutRequest(0L,
                List.of(sectionUpsert(section.getId(), "Floor",
                        List.of(seatUpsert(null, "B", 1, true)))),
                List.of());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/admin/venues/{venueId}/layout", venueId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(stale)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SF_409_CONFLICT"));

        assertThat(venueRepository.findById(venueId).orElseThrow().getLayoutVersion()).isEqualTo(1L);
        assertThat(seatRepository.count()).isEqualTo(seatsBefore);
    }

    @Test
    void shouldProveLostUpdateWithTwoConcurrentSaves() throws Exception {
        Venue venue = seedVenue("Race Hall", "CHI", 100);
        VenueSection section = seedSection(venue, "Main");
        Seat existing = seedSeat(section, "A", 1, 0, 0, true);
        UUID venueId = venue.getId();
        UUID sectionId = section.getId();

        SaveVenueLayoutRequest reqA = new SaveVenueLayoutRequest(0L,
                List.of(sectionUpsert(sectionId, "Name-A",
                        List.of(seatUpsert(existing.getId(), "A", 1, true),
                                seatUpsert(null, "A", 2, true)))),
                List.of());
        SaveVenueLayoutRequest reqB = new SaveVenueLayoutRequest(0L,
                List.of(sectionUpsert(sectionId, "Name-B",
                        List.of(seatUpsert(existing.getId(), "A", 1, true),
                                seatUpsert(null, "B", 1, true)))),
                List.of());

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger success = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger conflicts = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Throwable> unexpected = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        try {
            for (SaveVenueLayoutRequest req : List.of(reqA, reqB)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                            unexpected.compareAndSet(null, new IllegalStateException("start latch timeout"));
                            return;
                        }
                        layoutService.saveLayout(venueId, req);
                        success.incrementAndGet();
                    } catch (ConflictException e) {
                        conflicts.incrementAndGet();
                    } catch (Throwable t) {
                        unexpected.compareAndSet(null, t);
                    }
                }));
            }
            org.junit.jupiter.api.Assertions.assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            executor.shutdown();
            org.junit.jupiter.api.Assertions.assertTrue(
                    executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS));
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        org.junit.jupiter.api.Assertions.assertNull(unexpected.get(),
                () -> "unexpected failure in concurrent save: " + unexpected.get());
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        assertThat(venueRepository.findById(venueId).orElseThrow().getLayoutVersion()).isEqualTo(1L);
    }

    @Test
    void shouldRollbackPartialWriteAndKeepVersion() {
        Venue venue = seedVenue("Rollback Hall", "NYC", 100);
        VenueSection kept = seedSection(venue, "Kept");
        Seat keptSeat = seedSeat(kept, "A", 1, 0, 0, true);
        UUID venueId = venue.getId();
        long sectionsBefore = sectionRepository.count();
        long seatsBefore = seatRepository.count();

        // Two inactive sections with the same name pass pre-validation (rule 2 skips
        // inactive rows) but violate uq_venue_sections_venue_name at flush time.
        SaveVenueLayoutRequest request = new SaveVenueLayoutRequest(0L,
                List.of(
                        sectionUpsert(kept.getId(), "Kept",
                                List.of(seatUpsert(keptSeat.getId(), "A", 1, true))),
                        inactiveSectionUpsert("Dup"),
                        inactiveSectionUpsert("Dup")),
                List.of());

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> layoutService.saveLayout(venueId, request));

        assertThat(venueRepository.findById(venueId).orElseThrow().getLayoutVersion()).isEqualTo(0L);
        assertThat(sectionRepository.count()).isEqualTo(sectionsBefore);
        assertThat(seatRepository.count()).isEqualTo(seatsBefore);
        assertThat(sectionRepository.findById(kept.getId()).orElseThrow().getName()).isEqualTo("Kept");
    }

    @Test
    void shouldDeactivateOmittedRowsAndRemoveOnlyElements() {
        Venue venue = seedVenue("Omit Hall", "LA", 100);
        VenueSection kept = seedSection(venue, "Kept");
        VenueSection omitted = seedSection(venue, "Omitted");
        Seat keptSeat = seedSeat(kept, "A", 1, 0, 0, true);
        Seat omittedSeat = seedSeat(kept, "A", 2, 1, 0, true);
        Seat seatInOmitted = seedSeat(omitted, "A", 1, 0, 0, true);
        VenueLayoutElement element;
        try {
            element = elementRepository.saveAndFlush(VenueLayoutElement.builder()
                    .venue(venue).type(LayoutElementType.STAGE).label("Old")
                    .geometry(objectMapper.readTree("{\"x\":0,\"y\":0,\"width\":10,\"height\":10,\"rotationDeg\":0}"))
                    .zIndex(0).build());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        UUID venueId = venue.getId();

        // Omit the second section, omit one seat in the kept section, omit the element.
        SaveVenueLayoutRequest request = new SaveVenueLayoutRequest(0L,
                List.of(sectionUpsert(kept.getId(), "Kept",
                        List.of(seatUpsert(keptSeat.getId(), "A", 1, true)))),
                List.of());

        var response = layoutService.saveLayout(venueId, request);

        assertThat(response.layoutVersion()).isEqualTo(1L);
        // Inventory rows are retained with is_active=false.
        assertThat(sectionRepository.findById(omitted.getId()).orElseThrow().getIsActive()).isFalse();
        assertThat(seatRepository.findById(omittedSeat.getId()).orElseThrow().getIsActive()).isFalse();
        assertThat(seatRepository.findById(seatInOmitted.getId()).orElseThrow().getIsActive()).isFalse();
        // Non-bookable elements may be hard-removed.
        assertThat(elementRepository.findById(element.getId())).isEmpty();
        // Stable UUIDs preserved for kept rows.
        assertThat(seatRepository.findById(keptSeat.getId())).isPresent();
    }

    @Test
    void shouldKeepStableUuidsOnTransformRenameAndDeactivate() {
        Venue venue = seedVenue("Stable Hall", "NYC", 100);
        VenueSection section = seedSection(venue, "Floor");
        Seat seat = seedSeat(section, "A", 1, 0, 0, true);
        UUID venueId = venue.getId();

        SaveVenueLayoutRequest request = new SaveVenueLayoutRequest(0L,
                List.of(new SaveVenueLayoutRequest.SectionUpsert(section.getId(), "Moved", 2, 2, true,
                        new java.math.BigDecimal("500"), new java.math.BigDecimal("600"),
                        new java.math.BigDecimal("88"), new java.math.BigDecimal("88"),
                        new java.math.BigDecimal("15"), 5, null,
                        List.of(new SaveVenueLayoutRequest.SeatUpsert(seat.getId(), "Z", 9, 1, 1,
                                new java.math.BigDecimal("10"), new java.math.BigDecimal("10"), false)))),
                List.of());

        var response = layoutService.saveLayout(venueId, request);

        assertThat(response.sections().getFirst().sectionId()).isEqualTo(section.getId());
        assertThat(response.sections().getFirst().seats().getFirst().seatId()).isEqualTo(seat.getId());
        var reloaded = seatRepository.findById(seat.getId()).orElseThrow();
        assertThat(reloaded.getRowLabel()).isEqualTo("Z");
        assertThat(reloaded.getSeatNumber()).isEqualTo(9);
        assertThat(reloaded.getIsActive()).isFalse();
    }

    @Test
    void shouldPersistSeatAndSectionPermutationsWithoutReplacingStableIds() {
        Venue venue = seedVenue("Permutation Hall", "NYC", 100);
        VenueSection left = seedSection(venue, "Left");
        VenueSection right = seedSection(venue, "Right");
        Seat first = seedSeat(left, "A", 1, 0, 0, true);
        Seat second = seedSeat(left, "A", 2, 1, 0, true);

        SaveVenueLayoutRequest request = new SaveVenueLayoutRequest(0L, List.of(
                sectionUpsert(left.getId(), "Right", List.of(
                        seatUpsertAt(first.getId(), "A", 2, 1, 0, "44.000", "0.000", true),
                        seatUpsertAt(second.getId(), "A", 1, 0, 0, "0.000", "0.000", true))),
                sectionUpsert(right.getId(), "Left", List.of())), List.of());

        var response = layoutService.saveLayout(venue.getId(), request);

        assertThat(response.layoutVersion()).isEqualTo(1L);
        assertThat(sectionRepository.findById(left.getId()).orElseThrow().getName()).isEqualTo("Right");
        assertThat(sectionRepository.findById(right.getId()).orElseThrow().getName()).isEqualTo("Left");
        Seat reloadedFirst = seatRepository.findById(first.getId()).orElseThrow();
        Seat reloadedSecond = seatRepository.findById(second.getId()).orElseThrow();
        assertThat(reloadedFirst.getSeatNumber()).isEqualTo(2);
        assertThat(reloadedFirst.getGridX()).isEqualTo(1);
        assertThat(reloadedFirst.getPositionX()).isEqualByComparingTo("44.000");
        assertThat(reloadedSecond.getSeatNumber()).isEqualTo(1);
        assertThat(reloadedSecond.getGridX()).isZero();
        assertThat(reloadedSecond.getPositionX()).isEqualByComparingTo("0.000");
    }

    @Test
    void shouldPersistMultiSeatRenumberingShiftWithStableIds() {
        Venue venue = seedVenue("Shift Hall", "LA", 100);
        VenueSection section = seedSection(venue, "Floor");
        Seat first = seedSeat(section, "A", 1, 0, 0, true);
        Seat second = seedSeat(section, "A", 2, 1, 0, true);
        Seat third = seedSeat(section, "A", 3, 2, 0, true);
        SaveVenueLayoutRequest.SectionUpsert shifted = new SaveVenueLayoutRequest.SectionUpsert(
                section.getId(), "Floor", 1, 3, true,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                new java.math.BigDecimal("132.000"), new java.math.BigDecimal("44.000"),
                java.math.BigDecimal.ZERO, 0, null, List.of(
                seatUpsertAt(first.getId(), "A", 2, 1, 0, "44.000", "0.000", true),
                seatUpsertAt(second.getId(), "A", 3, 2, 0, "88.000", "0.000", true),
                seatUpsertAt(third.getId(), "A", 1, 0, 0, "0.000", "0.000", true)));

        layoutService.saveLayout(venue.getId(), new SaveVenueLayoutRequest(0L, List.of(shifted), List.of()));

        assertThat(seatRepository.findById(first.getId()).orElseThrow().getSeatNumber()).isEqualTo(2);
        assertThat(seatRepository.findById(second.getId()).orElseThrow().getSeatNumber()).isEqualTo(3);
        assertThat(seatRepository.findById(third.getId()).orElseThrow().getSeatNumber()).isEqualTo(1);
        assertThat(venueRepository.findById(venue.getId()).orElseThrow().getLayoutVersion()).isEqualTo(1L);
    }

    @Test
    void shouldWriteSectionCreatedEnvelopeWithFinalSeatCountAndRollbackItOnLateFailure() throws Exception {
        Venue venue = seedVenue("Outbox Layout Hall", "CHI", 100);
        SaveVenueLayoutRequest.SectionUpsert created = new SaveVenueLayoutRequest.SectionUpsert(
                null, "New Section", 1, 2, true,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                new java.math.BigDecimal("88.000"), new java.math.BigDecimal("44.000"),
                java.math.BigDecimal.ZERO, 0, null, List.of(
                seatUpsertAt(null, "A", 1, 0, 0, "0.000", "0.000", true),
                seatUpsertAt(null, "A", 2, 1, 0, "44.000", "0.000", true)));

        layoutService.saveLayout(venue.getId(), new SaveVenueLayoutRequest(0L, List.of(created), List.of()));

        List<OutboxEvent> events = outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        assertThat(events).hasSize(1);
        OutboxEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo("VenueSectionCreated");
        var envelope = objectMapper.readTree(event.getPayload());
        assertThat(envelope.path("eventType").asText()).isEqualTo("VenueSectionCreated");
        assertThat(envelope.path("aggregateId").asText()).isEqualTo(event.getAggregateId().toString());
        assertThat(envelope.path("payload").path("sectionId").asText()).isEqualTo(event.getAggregateId().toString());
        assertThat(envelope.path("payload").path("totalSeats").asInt()).isEqualTo(2);

        outboxEventRepository.deleteAll();
        long sectionsBefore = sectionRepository.count();
        long seatsBefore = seatRepository.count();
        long versionBefore = venueRepository.findById(venue.getId()).orElseThrow().getLayoutVersion();
        SaveVenueLayoutRequest failing = new SaveVenueLayoutRequest(versionBefore, List.of(
                inactiveSectionUpsert("Unique Before Failure"),
                inactiveSectionUpsert("Late Duplicate"),
                inactiveSectionUpsert("Late Duplicate")), List.of());

        assertThatThrownBy(() -> layoutService.saveLayout(venue.getId(), failing)).isInstanceOf(Exception.class);
        assertThat(outboxEventRepository.count()).isZero();
        assertThat(sectionRepository.count()).isEqualTo(sectionsBefore);
        assertThat(seatRepository.count()).isEqualTo(seatsBefore);
        assertThat(venueRepository.findById(venue.getId()).orElseThrow().getLayoutVersion()).isEqualTo(versionBefore);
    }

    @Test
    void shouldReturnOnlyCoherentEditorSnapshotAcrossConcurrentSave() throws Exception {
        Venue venue = seedVenue("Read Race Hall", "NYC", 100);
        VenueSection section = seedSection(venue, "Before");
        Seat seat = seedSeat(section, "A", 1, 0, 0, true);
        CountDownLatch beforeChildren = new CountDownLatch(1);
        CountDownLatch continueRead = new CountDownLatch(1);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> {
                layoutReadBarrier.arm(Thread.currentThread(), beforeChildren, continueRead);
                return layoutService.getEditableLayout(venue.getId());
            });
            assertThat(beforeChildren.await(10, TimeUnit.SECONDS)).isTrue();
            layoutService.saveLayout(venue.getId(), new SaveVenueLayoutRequest(0L,
                    List.of(sectionUpsert(section.getId(), "After", List.of(
                            seatUpsert(seat.getId(), "A", 1, true)))), List.of()));
            continueRead.countDown();
            var observed = future.get(10, TimeUnit.SECONDS);
            String observedName = observed.sections().getFirst().name();
            assertThat((observed.layoutVersion() == 0L && observedName.equals("Before"))
                    || (observed.layoutVersion() == 1L && observedName.equals("After"))).isTrue();
        } finally {
            continueRead.countDown();
            layoutReadBarrier.disarm();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldReturnOnlyCoherentPublicSnapshotAcrossConcurrentSave() throws Exception {
        Venue venue = seedVenue("Public Read Race Hall", "CHI", 100);
        VenueSection section = seedSection(venue, "Before");
        Seat seat = seedSeat(section, "A", 1, 0, 0, true);
        CountDownLatch beforeChildren = new CountDownLatch(1);
        CountDownLatch continueRead = new CountDownLatch(1);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> {
                layoutReadBarrier.arm(Thread.currentThread(), beforeChildren, continueRead);
                return publicLayoutService.getVenueLayout(venue.getId());
            });
            assertThat(beforeChildren.await(10, TimeUnit.SECONDS)).isTrue();
            layoutService.saveLayout(venue.getId(), new SaveVenueLayoutRequest(0L,
                    List.of(sectionUpsert(section.getId(), "After", List.of(
                            seatUpsert(seat.getId(), "A", 1, true)))), List.of()));
            continueRead.countDown();
            var observed = future.get(10, TimeUnit.SECONDS);
            String observedName = observed.sections().getFirst().name();
            assertThat((observed.layoutVersion() == 0L && observedName.equals("Before"))
                    || (observed.layoutVersion() == 1L && observedName.equals("After"))).isTrue();
        } finally {
            continueRead.countDown();
            layoutReadBarrier.disarm();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldValidateAgainstOneCoherentSnapshotAcrossConcurrentSave() throws Exception {
        Venue venue = seedVenue("Validation Race Hall", "LA", 100);
        VenueSection section = seedSection(venue, "Main");
        Seat seat = seedSeat(section, "A", 1, 0, 0, true);
        VenueLayoutElement element = elementRepository.saveAndFlush(VenueLayoutElement.builder()
                .venue(venue).type(LayoutElementType.STAGE).label("Stage")
                .geometry(objectMapper.readTree("{\"x\":0,\"y\":0,\"width\":10,\"height\":10,\"rotationDeg\":0}"))
                .zIndex(0).build());
        SaveVenueLayoutRequest validationRequest = new SaveVenueLayoutRequest(0L,
                List.of(sectionUpsert(section.getId(), "Main", List.of(
                        seatUpsert(seat.getId(), "A", 1, true)))),
                List.of(new SaveVenueLayoutRequest.LayoutElementUpsert(
                        element.getId(), LayoutElementType.STAGE, "Stage",
                        new SaveVenueLayoutRequest.Geometry(java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                                java.math.BigDecimal.TEN, java.math.BigDecimal.TEN, java.math.BigDecimal.ZERO), 0)));
        CountDownLatch beforeChildren = new CountDownLatch(1);
        CountDownLatch continueRead = new CountDownLatch(1);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> {
                layoutReadBarrier.arm(Thread.currentThread(), beforeChildren, continueRead);
                layoutService.validateLayout(venue.getId(), validationRequest);
                return null;
            });
            assertThat(beforeChildren.await(10, TimeUnit.SECONDS)).isTrue();
            layoutService.saveLayout(venue.getId(), new SaveVenueLayoutRequest(0L,
                    List.of(sectionUpsert(section.getId(), "Main", List.of(
                            seatUpsert(seat.getId(), "A", 1, true)))), List.of()));
            continueRead.countDown();
            future.get(10, TimeUnit.SECONDS);
        } finally {
            continueRead.countDown();
            layoutReadBarrier.disarm();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRejectSeatActivationUnderInactiveSectionWithoutVersionBump() throws Exception {
        Venue venue = seedVenue("Inactive Toggle Hall", "CHI", 100);
        VenueSection section = seedSection(venue, "Closed");
        Seat seat = seedSeat(section, "A", 1, 0, 0, true);
        mockMvc.perform(delete("/api/admin/venues/{venueId}/sections/{sectionId}", venue.getId(), section.getId())
                        .with(adminJwt()))
                .andExpect(status().isNoContent());
        long versionAfterDeactivate = venueRepository.findById(venue.getId()).orElseThrow().getLayoutVersion();

        mockMvc.perform(patch("/api/admin/venues/{venueId}/sections/{sectionId}/seats/{seatId}",
                        venue.getId(), section.getId(), seat.getId())
                        .with(adminJwt()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateSeatStatusRequest(true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("SF_400_INVALID_REQUEST"));

        assertThat(sectionRepository.findById(section.getId()).orElseThrow().getIsActive()).isFalse();
        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getIsActive()).isFalse();
        assertThat(venueRepository.findById(venue.getId()).orElseThrow().getLayoutVersion())
                .isEqualTo(versionAfterDeactivate);
        assertThat(seatRepository.countActiveSeatsByVenueId(venue.getId())).isZero();
    }

    @Test
    void shouldCanonicalizeNumericGeometryBeforeValidationPersistenceAndResponse() {
        Venue venue = seedVenue("Numeric Hall", "NYC", 100);
        VenueSection section = seedSection(venue, "Main");
        Seat seat = seedSeat(section, "A", 1, 0, 0, true);
        SaveVenueLayoutRequest.SectionUpsert update = new SaveVenueLayoutRequest.SectionUpsert(
                section.getId(), "Main", 2, 2, true,
                new java.math.BigDecimal("100000.0004"), new java.math.BigDecimal("10.0004"),
                new java.math.BigDecimal("88.0004"), new java.math.BigDecimal("88.0004"),
                new java.math.BigDecimal("10.0005"), 0, null, List.of(
                seatUpsertAt(seat.getId(), "A", 1, 0, 0, "1.2345", "2.3454", true)));

        var response = layoutService.saveLayout(venue.getId(),
                new SaveVenueLayoutRequest(0L, List.of(update), List.of()));
        VenueSection reloadedSection = sectionRepository.findById(section.getId()).orElseThrow();
        Seat reloadedSeat = seatRepository.findById(seat.getId()).orElseThrow();
        var responseSection = response.sections().getFirst();
        var responseSeat = responseSection.seats().getFirst();
        assertThat(responseSection.positionX()).isEqualByComparingTo("100000.000");
        assertThat(responseSection.positionX()).isEqualByComparingTo(reloadedSection.getPositionX());
        assertThat(responseSection.rotationDeg()).isEqualByComparingTo("10.001");
        assertThat(responseSeat.positionX()).isEqualByComparingTo("1.235");
        assertThat(responseSeat.positionX()).isEqualByComparingTo(reloadedSeat.getPositionX());
        assertThat(responseSeat.positionY()).isEqualByComparingTo("2.345");
    }

    @Test
    void shouldRejectActivePositionCollisionCausedByNumericRoundingBeforeMutation() {
        Venue venue = seedVenue("Numeric Collision Hall", "LA", 100);
        VenueSection section = seedSection(venue, "Main");
        Seat first = seedSeat(section, "A", 1, 0, 0, true);
        Seat second = seedSeat(section, "A", 2, 1, 0, true);
        SaveVenueLayoutRequest request = new SaveVenueLayoutRequest(0L, List.of(
                sectionUpsert(section.getId(), "Main", List.of(
                        seatUpsertAt(first.getId(), "A", 1, 0, 0, "1.0004", "1.0004", true),
                        seatUpsertAt(second.getId(), "A", 2, 1, 0, "1.00049", "1.00049", true)))), List.of());

        assertThatThrownBy(() -> layoutService.saveLayout(venue.getId(), request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("duplicates active position");
        assertThat(venueRepository.findById(venue.getId()).orElseThrow().getLayoutVersion()).isZero();
        assertThat(seatRepository.findById(first.getId()).orElseThrow().getPositionX()).isEqualByComparingTo("0.000");
        assertThat(seatRepository.findById(second.getId()).orElseThrow().getPositionX()).isEqualByComparingTo("44.000");
    }

    @Test
    void shouldRejectInvalidSnapshotWith400AndNoVersionBump() throws Exception {
        Venue venue = seedVenue("Invalid Hall", "CHI", 100);
        VenueSection section = seedSection(venue, "Main");
        seedSeat(section, "A", 1, 0, 0, true);
        UUID venueId = venue.getId();

        // Foreign section ID owned by another venue.
        Venue other = seedVenue("Other Hall", "LA", 100);
        VenueSection foreign = seedSection(other, "Foreign");

        SaveVenueLayoutRequest request = new SaveVenueLayoutRequest(0L,
                List.of(sectionUpsert(foreign.getId(), "Foreign",
                        List.of())),
                List.of());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/admin/venues/{venueId}/layout", venueId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("SF_400_INVALID_REQUEST"));

        assertThat(venueRepository.findById(venueId).orElseThrow().getLayoutVersion()).isEqualTo(0L);
    }

    @Test
    void shouldValidateWithoutWritingViaApi() throws Exception {
        Venue venue = seedVenue("Validate Hall", "NYC", 100);
        VenueSection section = seedSection(venue, "Main");
        seedSeat(section, "A", 1, 0, 0, true);
        UUID venueId = venue.getId();

        SaveVenueLayoutRequest request = new SaveVenueLayoutRequest(0L,
                List.of(sectionUpsert(section.getId(), "Main",
                        List.of(seatUpsert(null, "A", 1, true)))),
                List.of());

        mockMvc.perform(post("/api/admin/venues/{venueId}/layout/validation", venueId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        assertThat(venueRepository.findById(venueId).orElseThrow().getLayoutVersion()).isEqualTo(0L);
        assertThat(seatRepository.count()).isEqualTo(1L);
    }

    @Test
    void shouldExposeEditableLayoutAndKeepPublicCompat() throws Exception {
        Venue venue = seedVenue("Compat Hall", "NYC", 200);
        VenueSection section = seedSection(venue, "Balcony");
        seedSeat(section, "A", 1, 0, 0, true);
        UUID venueId = venue.getId();

        // Admin editor read includes layoutVersion.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/admin/venues/{venueId}/layout", venueId)
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venueId").value(venueId.toString()))
                .andExpect(jsonPath("$.layoutVersion").value(0))
                .andExpect(jsonPath("$.sections[0].seats[0].gridX").value(0))
                .andExpect(jsonPath("$.sections[0].seats[0].gridY").value(0));

        // Public read retains legacy grid fields.
        mockMvc.perform(get("/api/venues/{venueId}/layout", venueId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venueId").value(venueId.toString()))
                .andExpect(jsonPath("$.sections[0].seats[0].gridX").value(0))
                .andExpect(jsonPath("$.sections[0].seats[0].isActive").value(true));

        // Legacy section create bumps the layout version.
        mockMvc.perform(post("/api/admin/venues/{venueId}/sections", venueId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateVenueSectionRequest("Stalls", 1, 1))))
                .andExpect(status().isCreated());
        assertThat(venueRepository.findById(venueId).orElseThrow().getLayoutVersion()).isEqualTo(1L);

        // Legacy delete is now reversible deactivation + version bump.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                                "/api/admin/venues/{venueId}/sections/{sectionId}", venueId, section.getId())
                        .with(adminJwt()))
                .andExpect(status().isNoContent());
        assertThat(sectionRepository.findById(section.getId())).isPresent();
        assertThat(sectionRepository.findById(section.getId()).orElseThrow().getIsActive()).isFalse();
        assertThat(venueRepository.findById(venueId).orElseThrow().getLayoutVersion()).isEqualTo(2L);

        // Public read hides the deactivated section.
        mockMvc.perform(get("/api/venues/{venueId}/layout", venueId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.length()").value(1));
    }

    @Test
    void shouldRejectLayoutSaveWithoutAuth() throws Exception {
        Venue venue = seedVenue("Auth Hall", "NYC", 50);
        SaveVenueLayoutRequest request = new SaveVenueLayoutRequest(0L, List.of(), List.of());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/admin/venues/{venueId}/layout", venue.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}
