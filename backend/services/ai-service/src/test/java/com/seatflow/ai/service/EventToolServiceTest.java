package com.seatflow.ai.service;

import com.seatflow.ai.client.EventServiceClient;
import com.seatflow.ai.client.dto.EventDetailClientDto;
import com.seatflow.ai.client.dto.EventSessionClientDto;
import com.seatflow.ai.client.dto.EventSummaryClientDto;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;
import com.seatflow.ai.service.impl.EventToolServiceImpl;
import com.seatflow.ai.tool.dto.EventSessionsToolResult;
import com.seatflow.ai.tool.dto.EventToolResult;
import com.seatflow.ai.tool.dto.GetEventRequest;
import com.seatflow.ai.tool.dto.GetEventSessionsRequest;
import com.seatflow.ai.tool.dto.SearchEventsRequest;
import com.seatflow.ai.tool.dto.SearchEventsResult;
import com.seatflow.common.domain.dto.PagedResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventToolServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    private static final String TOKEN = "caller-jwt-token";
    private static final AiRequestContext CONTEXT =
            new AiRequestContext(TOKEN, "corr-1", "user-1");
    private static final AiRequestContext ANONYMOUS =
            new AiRequestContext(null, "corr-1", null);

    @Mock
    private EventServiceClient eventServiceClient;

    private EventToolService service;

    @BeforeEach
    void setUp() {
        service = new EventToolServiceImpl(eventServiceClient, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static EventSummaryClientDto summary(UUID id, String title, Instant nextSession) {
        return new EventSummaryClientDto(id, title, "THEATRE", null, nextSession,
                new BigDecimal("10.00"), new BigDecimal("50.00"), "EUR");
    }

    @Test
    @DisplayName("searchEvents trims criteria and maps the public catalog result")
    void searchMapsCatalogResult() {
        UUID id = UUID.randomUUID();
        when(eventServiceClient.searchEvents(eq("Hamlet"), eq("THEATRE"), eq(0), eq(5), eq(CONTEXT)))
                .thenReturn(PagedResult.of(
                        List.of(summary(id, "Hamlet", Instant.parse("2026-09-25T19:00:00Z"))),
                        0, 5, 1));

        SearchEventsResult result = service.searchEvents(
                new SearchEventsRequest("  Hamlet ", "theatre", null, null, null), CONTEXT);

        assertThat(result.events()).hasSize(1);
        assertThat(result.events().getFirst().eventId()).isEqualTo(id);
        assertThat(result.events().getFirst().title()).isEqualTo("Hamlet");
        assertThat(result.events().getFirst().status()).isEqualTo("PUBLISHED");
        assertThat(result.events().getFirst().nextSessionStartsAt())
                .isEqualTo(Instant.parse("2026-09-25T19:00:00Z"));
    }

    @Test
    @DisplayName("invalid date range never calls the Event Service")
    void invalidDateRangeNeverCallsDownstream() {
        SearchEventsRequest request = new SearchEventsRequest(null, null,
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 10), null);

        assertThatThrownBy(() -> service.searchEvents(request, CONTEXT))
                .isInstanceOf(AiToolException.class)
                .satisfies(ex -> assertThat(((AiToolException) ex).getError())
                        .isEqualTo(AiToolError.INVALID_TOOL_ARGUMENT));

        verify(eventServiceClient, never()).searchEvents(any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("search limit is server-clamped before exposing data to the model")
    void searchLimitIsBounded() {
        when(eventServiceClient.searchEvents(any(), any(), anyInt(), eq(20), any()))
                .thenReturn(PagedResult.of(List.of(), 0, 20, 0));

        SearchEventsResult result = service.searchEvents(
                new SearchEventsRequest(null, null, null, null, 100), CONTEXT);

        assertThat(result.events()).isEmpty();
        verify(eventServiceClient).searchEvents(null, null, 0, 20, CONTEXT);
    }

    @Test
    @DisplayName("over-long or control-character queries fail before any downstream call")
    void badQueriesFailBeforeDownstream() {
        assertThatThrownBy(() -> service.searchEvents(
                new SearchEventsRequest("x".repeat(101), null, null, null, null), CONTEXT))
                .isInstanceOf(AiToolException.class);
        assertThatThrownBy(() -> service.searchEvents(
                new SearchEventsRequest("Ham\u0007let", null, null, null, null), CONTEXT))
                .isInstanceOf(AiToolException.class);
        assertThatThrownBy(() -> service.searchEvents(
                new SearchEventsRequest(null, "CINEMA", null, null, null), CONTEXT))
                .isInstanceOf(AiToolException.class);

        verify(eventServiceClient, never()).searchEvents(any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("getEvent rejects a malformed UUID before any downstream call")
    void getEventRejectsMalformedUuid() {
        assertThatThrownBy(() -> service.getEvent(new GetEventRequest("not-a-uuid"), CONTEXT))
                .isInstanceOf(AiToolException.class)
                .satisfies(ex -> assertThat(((AiToolException) ex).getError())
                        .isEqualTo(AiToolError.INVALID_TOOL_ARGUMENT));

        verify(eventServiceClient, never()).getEvent(any(), any());
    }

    @Test
    @DisplayName("getEvent returns a compact snapshot and bounds long descriptions")
    void getEventBoundsDescription() {
        UUID id = UUID.randomUUID();
        UUID venueId = UUID.randomUUID();
        when(eventServiceClient.getEvent(eq(id), eq(CONTEXT))).thenReturn(new EventDetailClientDto(
                id, venueId, "Hamlet", "d".repeat(600), "THEATRE", "PUBLISHED",
                List.of(), List.of(), NOW, NOW));

        EventToolResult result = service.getEvent(new GetEventRequest(id.toString()), CONTEXT);

        assertThat(result.eventId()).isEqualTo(id);
        assertThat(result.venueId()).isEqualTo(venueId);
        assertThat(result.status()).isEqualTo("PUBLISHED");
        assertThat(result.descriptionSummary()).hasSize(503);
        // No bearer token may ever appear in tool output.
        assertThat(result.toString()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("getEventSessions returns session-scoped IDs and preserves timestamps exactly")
    void sessionsPreserveTimestamps() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant startsAt = Instant.parse("2026-10-05T19:00:00Z");
        Instant endsAt = Instant.parse("2026-10-05T21:30:00Z");
        when(eventServiceClient.listSessions(eq(eventId), eq(CONTEXT))).thenReturn(List.of(
                new EventSessionClientDto(sessionId, eventId, startsAt, endsAt,
                        Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-04T00:00:00Z"),
                        "SCHEDULED", "UTC", NOW, NOW)));

        EventSessionsToolResult result = service.getEventSessions(
                new GetEventSessionsRequest(eventId.toString(), null, null, null), CONTEXT);

        assertThat(result.eventId()).isEqualTo(eventId);
        assertThat(result.sessions()).hasSize(1);
        assertThat(result.sessions().getFirst().eventSessionId()).isEqualTo(sessionId);
        assertThat(result.sessions().getFirst().startsAt()).isEqualTo(startsAt);
        assertThat(result.sessions().getFirst().endsAt()).isEqualTo(endsAt);
        assertThat(result.sessions().getFirst().bookableHint()).isEqualTo("BOOKABLE");
    }

    @Test
    @DisplayName("cancelled or out-of-window sessions are honestly marked not bookable")
    void sessionsMarkUnbookableHonestly() {
        UUID eventId = UUID.randomUUID();
        when(eventServiceClient.listSessions(eq(eventId), eq(CONTEXT))).thenReturn(List.of(
                new EventSessionClientDto(UUID.randomUUID(), eventId,
                        Instant.parse("2026-10-05T19:00:00Z"), null, null, null,
                        "CANCELLED", null, NOW, NOW),
                new EventSessionClientDto(UUID.randomUUID(), eventId,
                        Instant.parse("2026-11-05T19:00:00Z"), null,
                        Instant.parse("2026-12-01T00:00:00Z"), null,
                        "SCHEDULED", null, NOW, NOW)));

        EventSessionsToolResult result = service.getEventSessions(
                new GetEventSessionsRequest(eventId.toString(), null, null, null), CONTEXT);

        assertThat(result.sessions()).extracting("bookableHint")
                .containsExactly("NOT_BOOKABLE", "SALES_CLOSED");
    }

    @Test
    @DisplayName("anonymous callers fail instead of silently gaining a privileged identity")
    void anonymousCallsFail() {
        assertThatThrownBy(() -> service.searchEvents(
                new SearchEventsRequest(null, null, null, null, null), ANONYMOUS))
                .isInstanceOf(AiToolException.class)
                .satisfies(ex -> assertThat(((AiToolException) ex).getError())
                        .isEqualTo(AiToolError.UNAUTHENTICATED));
        assertThatThrownBy(() -> service.getEvent(new GetEventRequest(UUID.randomUUID().toString()), null))
                .isInstanceOf(AiToolException.class);

        verify(eventServiceClient, never()).searchEvents(any(), any(), anyInt(), anyInt(), any());
        verify(eventServiceClient, never()).getEvent(any(), any());
        verify(eventServiceClient, never()).listSessions(any(), any());
    }

    @Test
    @DisplayName("date-bounded search keeps only sessions inside the inclusive window")
    void dateBoundsFilterOnNextSession() {
        ArgumentCaptor<Integer> sizeCaptor = ArgumentCaptor.forClass(Integer.class);
        when(eventServiceClient.searchEvents(any(), any(), anyInt(), sizeCaptor.capture(), any()))
                .thenReturn(PagedResult.of(List.of(
                        summary(UUID.randomUUID(), "Early", Instant.parse("2026-09-05T19:00:00Z")),
                        summary(UUID.randomUUID(), "Inside", Instant.parse("2026-09-15T19:00:00Z")),
                        summary(UUID.randomUUID(), "Late", Instant.parse("2026-10-05T19:00:00Z"))),
                        0, 5, 3));

        SearchEventsResult result = service.searchEvents(new SearchEventsRequest(null, null,
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 30), null), CONTEXT);

        assertThat(result.events()).extracting("title").containsExactly("Inside");
    }
}
