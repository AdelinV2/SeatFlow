package com.seatflow.ai.service;

import com.seatflow.ai.client.EventServiceClient;
import com.seatflow.ai.client.ReservationServiceClient;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;
import com.seatflow.ai.service.impl.EventToolServiceImpl;
import com.seatflow.ai.service.impl.ReservationToolServiceImpl;
import com.seatflow.ai.service.impl.SeatAvailabilityServiceImpl;
import com.seatflow.ai.service.seat.SeatCandidateAssembler;
import com.seatflow.ai.tool.dto.FindBestSeatsRequest;
import com.seatflow.ai.tool.dto.GetAvailableSeatsRequest;
import com.seatflow.ai.tool.dto.GetEventRequest;
import com.seatflow.ai.tool.dto.GetEventSessionsRequest;
import com.seatflow.ai.tool.dto.GetReservationRequest;
import com.seatflow.ai.tool.dto.SearchEventsRequest;
import com.seatflow.ai.tool.dto.SeatRankingStrategy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tool-argument abuse tests (TASK-P15-007 section 4.2): untrusted model input fails validation
 * before any downstream service call.
 */
@ExtendWith(MockitoExtension.class)
class AiToolArgumentAbuseTest {

    private static final AiRequestContext CONTEXT =
            new AiRequestContext("caller-jwt", "corr-1", "user-1");
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private EventServiceClient eventServiceClient;
    @Mock
    private ReservationServiceClient reservationServiceClient;
    @Mock
    private SeatCandidateAssembler assembler;

    private EventToolServiceImpl events;
    private SeatAvailabilityServiceImpl seats;
    private ReservationToolServiceImpl reservations;

    @BeforeEach
    void setUp() {
        events = new EventToolServiceImpl(eventServiceClient, CLOCK);
        seats = new SeatAvailabilityServiceImpl(assembler, null);
        reservations = new ReservationToolServiceImpl(reservationServiceClient,
                new AiMetrics(new SimpleMeterRegistry()));
    }

    @ParameterizedTest(name = "invalid UUID rejected: {0}")
    @ValueSource(strings = {"not-a-uuid", "", "   ", "http://reservation-service/api/seats/1",
            "123e4567-e89b-12d3-a456-42661417400Z"})
    @DisplayName("invalid UUIDs fail before downstream calls")
    void invalidUuidsRejected(String bad) {
        assertInvalid(() -> events.getEvent(new GetEventRequest(bad), CONTEXT));
        assertInvalid(() -> events.getEventSessions(new GetEventSessionsRequest(bad, null, null, null), CONTEXT));
        assertInvalid(() -> seats.getAvailableSeats(
                new GetAvailableSeatsRequest(bad, null, null, null, null, null), CONTEXT));
        assertInvalid(() -> seats.findBestSeats(new FindBestSeatsRequest(
                bad, 2, null, null, null, null, null, SeatRankingStrategy.CLOSEST_TO_STAGE), CONTEXT));
        assertInvalid(() -> reservations.getReservation(new GetReservationRequest(bad), CONTEXT));

        verify(eventServiceClient, never()).getEvent(any(), any());
        verify(eventServiceClient, never()).listSessions(any(), any());
        verify(eventServiceClient, never()).searchEvents(any(), any(), anyInt(), anyInt(), any());
        verify(reservationServiceClient, never()).getReservation(any(), any());
        verify(assembler, never()).assemble(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("oversized search strings fail before the Event Service is called")
    void oversizedSearchRejected() {
        assertInvalid(() -> events.searchEvents(
                new SearchEventsRequest("x".repeat(101), null, null, null, null), CONTEXT));
        assertInvalid(() -> events.searchEvents(
                new SearchEventsRequest("Hamlet\nINJECT", null, null, null, null), CONTEXT));
        verify(eventServiceClient, never()).searchEvents(any(), any(), anyInt(), anyInt(), any());
    }

    @ParameterizedTest(name = "quantity {0} rejected")
    @ValueSource(ints = {0, -1, 11, 100})
    @DisplayName("out-of-range quantities fail before downstream calls")
    void quantityBoundsEnforced(int quantity) {
        assertInvalid(() -> seats.findBestSeats(new FindBestSeatsRequest(
                UUID.randomUUID().toString(), quantity, null, null, null, null, null,
                SeatRankingStrategy.BEST_VALUE), CONTEXT));
        verify(assembler, never()).assemble(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("negative budgets fail before downstream calls")
    void negativeBudgetsRejected() {
        assertInvalid(() -> seats.findBestSeats(new FindBestSeatsRequest(
                UUID.randomUUID().toString(), 2, -1L, null, null, null, null,
                SeatRankingStrategy.BEST_VALUE), CONTEXT));
        assertInvalid(() -> seats.getAvailableSeats(new GetAvailableSeatsRequest(
                UUID.randomUUID().toString(), null, null, -5L, null, null), CONTEXT));
        verify(assembler, never()).assemble(any(), any(), any(), any(), any());
    }

    @ParameterizedTest(name = "currency ''{0}'' rejected")
    @ValueSource(strings = {"EURO", "US", "usdX", "12", "e u r"})
    @DisplayName("malformed currencies fail before downstream calls")
    void malformedCurrenciesRejected(String currency) {
        assertInvalid(() -> seats.findBestSeats(new FindBestSeatsRequest(
                UUID.randomUUID().toString(), 2, null, currency, null, null, null,
                SeatRankingStrategy.BEST_VALUE), CONTEXT));
        verify(assembler, never()).assemble(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("excessive result limits are clamped, never passed through")
    void excessiveLimitsClamped() {
        var page = com.seatflow.common.domain.dto.PagedResult
                .<com.seatflow.ai.client.dto.EventSummaryClientDto>of(List.of(), 0, 20, 0);
        org.mockito.Mockito.when(eventServiceClient.searchEvents(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(page);
        events.searchEvents(new SearchEventsRequest(null, null, null, null, 10000), CONTEXT);

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(eventServiceClient).searchEvents(any(), any(), anyInt(), limit.capture(), any());
        assertThat(limit.getValue()).isEqualTo(20);
    }

    @Test
    @DisplayName("model-provided host/base URLs have no field to travel through")
    void noUrlOrIdentityFieldsOnToolInputs() {
        List<Class<?>> inputs = List.of(
                SearchEventsRequest.class, GetEventRequest.class, GetEventSessionsRequest.class,
                GetAvailableSeatsRequest.class, FindBestSeatsRequest.class, GetReservationRequest.class);
        Set<String> forbidden = Set.of("url", "host", "baseurl", "endpoint", "token", "jwt",
                "role", "userid", "username", "password", "apikey", "secret", "cookie", "header");
        for (Class<?> input : inputs) {
            Set<String> components = Arrays.stream(input.getRecordComponents())
                    .map(RecordComponent::getName)
                    .map(name -> name.toLowerCase().replace("_", ""))
                    .collect(Collectors.toSet());
            assertThat(components)
                    .as("tool input %s must expose no URL/identity fields", input.getSimpleName())
                    .doesNotContainAnyElementsOf(forbidden);
        }
        // ... and the event client ignores caller-controlled hosts (config base URL only).
        assertThat(EventServiceClient.class.getMethods())
                .noneMatch(method -> Arrays.stream(method.getParameterTypes())
                        .anyMatch(type -> type == java.net.URL.class || type == java.net.URI.class));
    }

    @Test
    @DisplayName("malformed structured input (null requests) fails safely")
    void nullRequestsRejected() {
        assertInvalid(() -> events.searchEvents(null, CONTEXT));
        assertInvalid(() -> events.getEvent(null, CONTEXT));
        assertInvalid(() -> events.getEventSessions(null, CONTEXT));
        assertInvalid(() -> seats.getAvailableSeats(null, CONTEXT));
        assertInvalid(() -> seats.findBestSeats(null, CONTEXT));
        assertInvalid(() -> reservations.getReservation(null, CONTEXT));
        verify(eventServiceClient, never()).searchEvents(any(), any(), anyInt(), anyInt(), any());
        verify(assembler, never()).assemble(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("anonymous callers fail without downstream calls")
    void anonymousCallersRejected() {
        AiRequestContext anonymous = new AiRequestContext(null, "corr-1", null);
        String id = UUID.randomUUID().toString();
        assertThatThrownBy(() -> events.searchEvents(new SearchEventsRequest("x", null, null, null, null), anonymous))
                .isInstanceOf(AiToolException.class)
                .satisfies(ex -> assertThat(((AiToolException) ex).getError())
                        .isEqualTo(AiToolError.UNAUTHENTICATED));
        assertThatThrownBy(() -> events.getEvent(new GetEventRequest(id), anonymous))
                .isInstanceOf(AiToolException.class);
        assertThatThrownBy(() -> seats.findBestSeats(new FindBestSeatsRequest(
                id, 2, null, null, null, null, null, SeatRankingStrategy.BEST_VALUE), anonymous))
                .isInstanceOf(AiToolException.class);
        assertThatThrownBy(() -> reservations.getReservation(new GetReservationRequest(id), anonymous))
                .isInstanceOf(AiToolException.class);
        verify(eventServiceClient, never()).searchEvents(anyString(), any(), anyInt(), anyInt(), any());
        verify(assembler, never()).assemble(any(), any(), any(), any(), any());
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable executable) {
        assertThatThrownBy(executable)
                .isInstanceOf(AiToolException.class)
                .satisfies(ex -> assertThat(((AiToolException) ex).getError())
                        .isEqualTo(AiToolError.INVALID_TOOL_ARGUMENT));
    }
}
