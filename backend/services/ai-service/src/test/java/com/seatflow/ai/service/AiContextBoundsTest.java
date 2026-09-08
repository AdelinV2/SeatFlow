package com.seatflow.ai.service;

import com.seatflow.ai.api.dto.AssistantChatResponse;
import com.seatflow.ai.client.EventServiceClient;
import com.seatflow.ai.client.ReservationAvailabilityClient;
import com.seatflow.ai.client.dto.PricingTierClientDto;
import com.seatflow.ai.client.dto.SeatAvailabilityClientDto;
import com.seatflow.ai.client.dto.SeatMapClientDto;
import com.seatflow.ai.client.dto.SessionBookingContextClientDto;
import com.seatflow.ai.config.AiProviderConfig;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.orchestration.AssistantCardAssembler;
import com.seatflow.ai.orchestration.AssistantConversationProperties;
import com.seatflow.ai.orchestration.AssistantModelClient;
import com.seatflow.ai.orchestration.AssistantOrchestrator;
import com.seatflow.ai.orchestration.AssistantPromptFactory;
import com.seatflow.ai.orchestration.AssistantProviderErrorMapper;
import com.seatflow.ai.orchestration.AssistantToolObservation;
import com.seatflow.ai.orchestration.AssistantToolRegistry;
import com.seatflow.ai.orchestration.AssistantState;
import com.seatflow.ai.orchestration.ConversationStore;
import com.seatflow.ai.proposal.ProposalStore;
import com.seatflow.ai.proposal.ReservationProposalProperties;
import com.seatflow.ai.service.impl.ProposalServiceImpl;
import com.seatflow.ai.service.impl.SeatAvailabilityServiceImpl;
import com.seatflow.ai.service.seat.SeatCandidateAssembler;
import com.seatflow.ai.service.seat.impl.SeatRankingServiceImpl;
import com.seatflow.ai.tool.dto.AvailableSeatsResult;
import com.seatflow.ai.tool.dto.FindBestSeatsRequest;
import com.seatflow.ai.tool.dto.FindBestSeatsResult;
import com.seatflow.ai.tool.dto.GetAvailableSeatsRequest;
import com.seatflow.ai.tool.dto.SeatRankingStrategy;
import com.seatflow.common.domain.exception.ValidationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.yaml.snakeyaml.Yaml;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Performance/context-bound tests (TASK-P15-007 section 12): every hop between user input and the
 * model is bounded, and a large venue never leaks its full unfiltered inventory into model
 * context.
 */
@ExtendWith(MockitoExtension.class)
class AiContextBoundsTest {

    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    private static final AiRequestContext CONTEXT =
            new AiRequestContext("caller-jwt", "corr-1", "user-1");

    @Mock
    private EventServiceClient eventServiceClient;
    @Mock
    private ReservationAvailabilityClient availabilityClient;
    @Mock
    private AssistantPromptFactory promptFactory;
    @Mock
    private AssistantToolRegistry toolRegistry;
    @Mock
    private AssistantModelClient modelClient;
    @Mock
    private AiStatusService statusService;

    private SeatCandidateAssembler assembler;
    private SeatAvailabilityServiceImpl seats;

    private UUID sessionId;
    private UUID eventId;
    private List<UUID> venueSeatIds;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assembler = new SeatCandidateAssembler(eventServiceClient, availabilityClient,
                new SimpleMeterRegistry(), clock);
        seats = new SeatAvailabilityServiceImpl(assembler, new SeatRankingServiceImpl());
        sessionId = UUID.randomUUID();
        eventId = UUID.randomUUID();
        venueSeatIds = new ArrayList<>();
    }

    @Test
    @DisplayName("user message is bounded to 2000 characters after trim")
    void messageLengthBounded() {
        var orchestrator = orchestrator();
        assertThatThrownBy(() -> orchestrator.chat(null, "x".repeat(2001), "user-1", CONTEXT))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> orchestrator.chat(null, "   ", "user-1", CONTEXT))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("provider timeout default is bounded (15s)")
    void providerTimeoutBounded() {
        assertThat(new AiProviderConfig()
                .aiProviderTimeouts(Duration.ofSeconds(15)).requestTimeout())
                .isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("application.yaml keeps provider/context bounds (timeout, no retries, max output, memory, limits)")
    void applicationYamlBounds() throws Exception {
        Map<String, Object> yaml;
        try (var stream = getClass().getResourceAsStream("/application.yaml")) {
            assertThat(stream).as("application.yaml on test classpath").isNotNull();
            yaml = new Yaml().load(stream);
        }
        assertThat(navigate(yaml, "spring", "ai", "openai", "timeout")).isEqualTo("15s");
        assertThat(navigate(yaml, "spring", "ai", "openai", "max-retries")).isEqualTo(0);
        // Environment-mapped values keep safe defaults after the colon.
        assertThat(String.valueOf(navigate(yaml, "spring", "ai", "openai", "chat", "max-completion-tokens")))
                .endsWith(":600}");
        assertThat(String.valueOf(navigate(yaml, "seatflow", "ai", "conversation", "max-messages")))
                .endsWith(":24}");
        assertThat(String.valueOf(navigate(yaml, "seatflow", "ai", "conversation", "max-active-conversations")))
                .endsWith(":500}");
        assertThat(String.valueOf(navigate(yaml, "seatflow", "ai", "rate-limit", "chat-requests-per-minute")))
                .endsWith(":60}");
        assertThat(String.valueOf(navigate(yaml, "seatflow", "ai", "rate-limit", "confirm-attempts-per-minute")))
                .endsWith(":30}");
    }

    @Test
    @DisplayName("large venue: model context excludes the full unfiltered seat inventory")
    @SuppressWarnings("unchecked")
    void largeVenueExcludedFromModelContext() {
        stubVenue(3000);

        // Server-side tools stay bounded over the full snapshot.
        AvailableSeatsResult available = seats.getAvailableSeats(
                new GetAvailableSeatsRequest(sessionId.toString(), null, null, null, "EUR", 10000),
                CONTEXT);
        assertThat(available.seats()).hasSizeLessThanOrEqualTo(50);

        FindBestSeatsResult ranked = seats.findBestSeats(new FindBestSeatsRequest(
                sessionId.toString(), 2, null, "EUR", null, null, null,
                SeatRankingStrategy.CLOSEST_TO_STAGE), CONTEXT);
        assertThat(ranked.candidates()).hasSizeLessThanOrEqualTo(3);
        assertThat(ranked.candidates().getFirst().seatIds()).hasSize(2);

        // The orchestrator turn that presents this candidate sends none of the venue
        // inventory to the model: only system prompt, bounded history, the short user
        // message, and the allow-listed tool names travel in the provider request.
        var orchestrator = orchestrator();
        when(modelClient.execute(any())).thenReturn(new AssistantModelClient.ModelTurnResult(
                "Two seats together.", null, null, null, null, ranked));

        AssistantChatResponse response =
                orchestrator.chat(null, "2 seats together", "user-1", CONTEXT);
        assertThat(response.state()).isEqualTo(AssistantState.CONFIRMATION_REQUIRED);

        ArgumentCaptor<AssistantModelClient.ModelTurnRequest> captor =
                ArgumentCaptor.forClass(AssistantModelClient.ModelTurnRequest.class);
        verify(modelClient).execute(captor.capture());
        String providerContext = captor.getValue().systemPrompt()
                + captor.getValue().userMessage()
                + captor.getValue().history().toString()
                + captor.getValue().allowedToolNames().toString();
        // Sample the venue widely: none of the 3000 seat IDs may reach the model.
        List<String> leaked = venueSeatIds.stream()
                .filter(id -> providerContext.contains(id.toString()))
                .limit(5)
                .map(UUID::toString)
                .toList();
        assertThat(leaked).as("seat IDs leaked into model context").isEmpty();
        assertThat(providerContext.length()).isLessThan(10_000);
    }

    private AssistantOrchestrator orchestrator() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(24)
                .build();
        var conversations = new ConversationStore(
                new AssistantConversationProperties(24, Duration.ofMinutes(30), 500), clock, memory);
        var proposals = new ProposalStore(
                new ReservationProposalProperties(Duration.ofMinutes(5), 500, Duration.ofMinutes(1)), clock);
        var metrics = new AiMetrics(new SimpleMeterRegistry());
        org.mockito.Mockito.lenient().when(promptFactory.systemPrompt()).thenReturn("prompt");
        org.mockito.Mockito.lenient().when(statusService.isChatAvailable()).thenReturn(true);
        org.mockito.Mockito.lenient().when(statusService.isEnabled()).thenReturn(true);
        return new AssistantOrchestrator(conversations, memory, promptFactory,
                toolRegistry, new AssistantCardAssembler(), modelClient,
                new AssistantToolObservation(), new AssistantProviderErrorMapper(),
                statusService, new ProposalServiceImpl(proposals, metrics), metrics, clock);
    }

    private void stubVenue(int totalSeats) {
        int perSection = totalSeats / 3;
        List<SeatMapClientDto.SeatMapSection> sections = new ArrayList<>();
        for (int section = 0; section < 3; section++) {
            UUID sectionId = UUID.randomUUID();
            List<SeatMapClientDto.SeatMapSeat> mapSeats = new ArrayList<>();
            for (int number = 1; number <= perSection; number++) {
                UUID seatId = UUID.randomUUID();
                venueSeatIds.add(seatId);
                mapSeats.add(new SeatMapClientDto.SeatMapSeat(seatId, "A", number,
                        number, 1, true, new BigDecimal(number), BigDecimal.ONE));
            }
            var tier = new PricingTierClientDto(UUID.randomUUID(), sectionId, "STD",
                    new BigDecimal("15.00"), "EUR");
            sections.add(new SeatMapClientDto.SeatMapSection(sectionId, "Section-" + section,
                    1, perSection, true, BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("100"), new BigDecimal("10"), BigDecimal.ZERO, 0,
                    mapSeats, List.of(tier)));
        }
        var seatMap = new SeatMapClientDto(eventId, UUID.randomUUID(), "Big Show", "PUBLISHED",
                "Arena", totalSeats, (long) totalSeats, sections, 1L, List.of());
        var booking = new SessionBookingContextClientDto(sessionId, eventId, "PUBLISHED", "SCHEDULED",
                NOW.plus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(2)).plus(java.time.Duration.ofHours(2)),
                null, null, UUID.randomUUID());
        when(eventServiceClient.getSessionBookingContext(any(), any())).thenReturn(booking);
        when(eventServiceClient.getSeatMap(any(), any())).thenReturn(seatMap);
        when(availabilityClient.getSeatAvailability(any(), any()))
                .thenReturn(new SeatAvailabilityClientDto(sessionId, eventId, List.of()));
    }

    @SuppressWarnings("unchecked")
    private static Object navigate(Map<String, Object> root, String... path) {
        Object current = root;
        for (String key : path) {
            current = ((Map<String, Object>) current).get(key);
        }
        return current;
    }
}
