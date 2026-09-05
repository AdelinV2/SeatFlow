package com.seatflow.event.service;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.event.mapper.EventSessionMapper;
import com.seatflow.event.model.entity.Event;
import com.seatflow.event.model.entity.EventSession;
import com.seatflow.event.model.enums.EventCategory;
import com.seatflow.event.model.enums.EventSessionStatus;
import com.seatflow.event.model.enums.EventStatus;
import com.seatflow.event.repository.EventRepository;
import com.seatflow.event.repository.EventSessionRepository;
import com.seatflow.event.service.impl.EventSessionServiceImpl;
import com.seatflow.event.web.dto.request.CreateEventSessionRequest;
import com.seatflow.event.web.dto.request.UpdateEventSessionRequest;
import com.seatflow.event.web.dto.response.EventSessionResponse;
import com.seatflow.event.web.dto.response.SessionBookingContextResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventSessionServiceImplTest {

    @Mock
    private EventRepository eventRepository;
    @Mock
    private EventSessionRepository eventSessionRepository;
    @Mock
    private EventSessionMapper eventSessionMapper;

    @InjectMocks
    private EventSessionServiceImpl eventSessionService;

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    private Event buildEvent(EventStatus status) {
        return Event.builder()
                .id(EVENT_ID)
                .venueId(UUID.randomUUID())
                .title("Hamlet")
                .description("A play")
                .category(EventCategory.THEATRE)
                .status(status)
                .build();
    }

    private EventSession buildSession(Event event, EventSessionStatus status,
                                      Instant startsAt, Instant endsAt) {
        return EventSession.builder()
                .id(SESSION_ID)
                .event(event)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .status(status)
                .legacyBackfill(false)
                .build();
    }

    private CreateEventSessionRequest createRequest(Instant startsAt, Instant endsAt) {
        return new CreateEventSessionRequest(startsAt, endsAt, null, null, null);
    }

    private EventSessionResponse dummyResponse(UUID id) {
        return new EventSessionResponse(id, EVENT_ID, Instant.now().plusSeconds(86400),
                Instant.now().plusSeconds(86400 + 7200), null, null,
                EventSessionStatus.SCHEDULED, null, Instant.now(), Instant.now());
    }

    @Test
    void createSession_createsTwoSessionsWithDistinctIds() {
        Event event = buildEvent(EventStatus.DRAFT);
        Instant firstStart = Instant.now().plusSeconds(86400);
        Instant secondStart = Instant.now().plusSeconds(2 * 86400);
        EventSession first = buildSession(event, EventSessionStatus.SCHEDULED,
                firstStart, firstStart.plusSeconds(7200));
        first.setId(UUID.randomUUID());
        EventSession second = buildSession(event, EventSessionStatus.SCHEDULED,
                secondStart, secondStart.plusSeconds(7200));
        second.setId(UUID.randomUUID());
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(eventSessionMapper.toEntity(any(CreateEventSessionRequest.class)))
                .thenReturn(EventSession.builder().build());
        when(eventSessionRepository.save(any(EventSession.class))).thenReturn(first, second);
        when(eventSessionMapper.toResponse(first)).thenReturn(dummyResponse(first.getId()));
        when(eventSessionMapper.toResponse(second)).thenReturn(dummyResponse(second.getId()));

        EventSessionResponse firstResponse =
                eventSessionService.createSession(EVENT_ID, createRequest(firstStart, firstStart.plusSeconds(7200)));
        EventSessionResponse secondResponse =
                eventSessionService.createSession(EVENT_ID, createRequest(secondStart, secondStart.plusSeconds(7200)));

        assertThat(firstResponse.id()).isNotEqualTo(secondResponse.id());
        ArgumentCaptor<EventSession> captor = ArgumentCaptor.forClass(EventSession.class);
        verify(eventSessionRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(saved -> {
            assertThat(saved.getStatus()).isEqualTo(EventSessionStatus.SCHEDULED);
            assertThat(saved.getLegacyBackfill()).isFalse();
            assertThat(saved.getEvent()).isEqualTo(event);
        });
    }

    @Test
    void createSession_unknownEvent_rejects404() {
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventSessionService.createSession(EVENT_ID,
                createRequest(Instant.now().plusSeconds(86400), Instant.now().plusSeconds(86400 + 7200))))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(eventSessionRepository, never()).save(any());
    }

    @Test
    void createSession_terminalEvent_rejects400() {
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(buildEvent(EventStatus.CANCELLED)));

        assertThatThrownBy(() -> eventSessionService.createSession(EVENT_ID,
                createRequest(Instant.now().plusSeconds(86400), Instant.now().plusSeconds(86400 + 7200))))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
    }

    @Test
    void createSession_startsAtNotBeforeEndsAt_rejects400() {
        Instant now = Instant.now();
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(buildEvent(EventStatus.DRAFT)));

        assertThatThrownBy(() -> eventSessionService.createSession(EVENT_ID, createRequest(now, now)))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
    }

    @Test
    void createSession_saleWindowOutOfOrder_rejects400() {
        Instant start = Instant.now().plusSeconds(86400);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(buildEvent(EventStatus.DRAFT)));

        CreateEventSessionRequest request = new CreateEventSessionRequest(
                start, start.plusSeconds(7200), start.minusSeconds(7200), start.minusSeconds(7200), null);

        assertThatThrownBy(() -> eventSessionService.createSession(EVENT_ID, request))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
    }

    @Test
    void createSession_saleEndsAfterStartsAt_rejects400() {
        Instant start = Instant.now().plusSeconds(86400);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(buildEvent(EventStatus.DRAFT)));

        CreateEventSessionRequest request = new CreateEventSessionRequest(
                start, start.plusSeconds(7200), start.minusSeconds(7200), start.plusSeconds(60), null);

        assertThatThrownBy(() -> eventSessionService.createSession(EVENT_ID, request))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
    }

    @Test
    void createSession_saleStartsAfterStartsAt_rejects400() {
        Instant start = Instant.now().plusSeconds(86400);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(buildEvent(EventStatus.DRAFT)));

        CreateEventSessionRequest request = new CreateEventSessionRequest(
                start, start.plusSeconds(7200), start.plusSeconds(3600), null, null);

        assertThatThrownBy(() -> eventSessionService.createSession(EVENT_ID, request))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
    }

    @Test
    void updateSession_saleStartsAfterStartsAt_rejects400() {
        Event event = buildEvent(EventStatus.DRAFT);
        Instant now = Instant.now();
        EventSession session = buildSession(event, EventSessionStatus.SCHEDULED,
                now.plusSeconds(86400), now.plusSeconds(86400 + 7200));
        session.setSaleStartsAt(now.plusSeconds(3600));
        when(eventSessionRepository.findByIdAndEvent_Id(SESSION_ID, EVENT_ID))
                .thenReturn(Optional.of(session));

        Instant newStart = now.plusSeconds(2 * 86400);
        UpdateEventSessionRequest request = new UpdateEventSessionRequest(
                newStart, newStart.plusSeconds(7200), newStart.plusSeconds(3600), null, null);

        assertThatThrownBy(() -> eventSessionService.updateSession(EVENT_ID, SESSION_ID, request))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
    }

    @Test
    void createSession_invalidTimezone_rejects400() {
        Instant start = Instant.now().plusSeconds(86400);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(buildEvent(EventStatus.DRAFT)));

        CreateEventSessionRequest request = new CreateEventSessionRequest(
                start, start.plusSeconds(7200), null, null, "Not/AZone");

        assertThatThrownBy(() -> eventSessionService.createSession(EVENT_ID, request))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
    }

    @Test
    void updateSession_mismatchedPair_rejects404() {
        UUID otherEventId = UUID.randomUUID();
        when(eventSessionRepository.findByIdAndEvent_Id(SESSION_ID, otherEventId)).thenReturn(Optional.empty());
        Instant start = Instant.now().plusSeconds(86400);

        assertThatThrownBy(() -> eventSessionService.updateSession(otherEventId, SESSION_ID,
                new UpdateEventSessionRequest(start, start.plusSeconds(7200), null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateSession_afterSalesOpen_rejects409() {
        Event event = buildEvent(EventStatus.DRAFT);
        Instant now = Instant.now();
        EventSession session = buildSession(event, EventSessionStatus.SCHEDULED,
                now.plusSeconds(3600), now.plusSeconds(7200 + 3600));
        session.setSaleStartsAt(now.minusSeconds(60));
        when(eventSessionRepository.findByIdAndEvent_Id(SESSION_ID, EVENT_ID))
                .thenReturn(Optional.of(session));

        assertThatThrownBy(() -> eventSessionService.updateSession(EVENT_ID, SESSION_ID,
                new UpdateEventSessionRequest(session.getStartsAt(), session.getEndsAt(), null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONFLICT);
    }

    @Test
    void updateSession_publishedEventWithoutFutureSaleStart_rejects409() {
        Event event = buildEvent(EventStatus.PUBLISHED);
        Instant now = Instant.now();
        EventSession session = buildSession(event, EventSessionStatus.SCHEDULED,
                now.plusSeconds(86400), now.plusSeconds(86400 + 7200));
        when(eventSessionRepository.findByIdAndEvent_Id(SESSION_ID, EVENT_ID))
                .thenReturn(Optional.of(session));

        assertThatThrownBy(() -> eventSessionService.updateSession(EVENT_ID, SESSION_ID,
                new UpdateEventSessionRequest(session.getStartsAt(), session.getEndsAt(), null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONFLICT);
    }

    @Test
    void updateSession_pastSession_rejects409() {
        Event event = buildEvent(EventStatus.DRAFT);
        Instant now = Instant.now();
        EventSession session = buildSession(event, EventSessionStatus.SCHEDULED,
                now.minusSeconds(7200), now.minusSeconds(3600));
        when(eventSessionRepository.findByIdAndEvent_Id(SESSION_ID, EVENT_ID))
                .thenReturn(Optional.of(session));

        assertThatThrownBy(() -> eventSessionService.updateSession(EVENT_ID, SESSION_ID,
                new UpdateEventSessionRequest(session.getStartsAt(), session.getEndsAt(), null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONFLICT);
    }

    @Test
    void updateSession_cancelledSession_rejects409() {
        Event event = buildEvent(EventStatus.DRAFT);
        Instant now = Instant.now();
        EventSession session = buildSession(event, EventSessionStatus.CANCELLED,
                now.plusSeconds(86400), now.plusSeconds(86400 + 7200));
        when(eventSessionRepository.findByIdAndEvent_Id(SESSION_ID, EVENT_ID))
                .thenReturn(Optional.of(session));

        assertThatThrownBy(() -> eventSessionService.updateSession(EVENT_ID, SESSION_ID,
                new UpdateEventSessionRequest(session.getStartsAt(), session.getEndsAt(), null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONFLICT);
    }

    @Test
    void updateSession_unlockedWithFutureSaleWindow_succeeds() {
        Event event = buildEvent(EventStatus.DRAFT);
        Instant now = Instant.now();
        EventSession session = buildSession(event, EventSessionStatus.SCHEDULED,
                now.plusSeconds(86400), now.plusSeconds(86400 + 7200));
        session.setSaleStartsAt(now.plusSeconds(3600));
        when(eventSessionRepository.findByIdAndEvent_Id(SESSION_ID, EVENT_ID))
                .thenReturn(Optional.of(session));
        when(eventSessionRepository.save(any(EventSession.class))).thenReturn(session);
        when(eventSessionMapper.toResponse(session)).thenReturn(dummyResponse(SESSION_ID));

        Instant newStart = now.plusSeconds(2 * 86400);
        EventSessionResponse response = eventSessionService.updateSession(EVENT_ID, SESSION_ID,
                new UpdateEventSessionRequest(newStart, newStart.plusSeconds(7200),
                        now.plusSeconds(3600), null, "Europe/Berlin"));

        assertThat(response).isNotNull();
        assertThat(session.getStartsAt()).isEqualTo(newStart);
        assertThat(session.getTimezone()).isEqualTo("Europe/Berlin");
    }

    @Test
    void deleteSession_afterSalesOpen_rejects409() {
        Event event = buildEvent(EventStatus.DRAFT);
        Instant now = Instant.now();
        EventSession session = buildSession(event, EventSessionStatus.SCHEDULED,
                now.plusSeconds(3600), now.plusSeconds(7200 + 3600));
        session.setSaleStartsAt(now.minusSeconds(60));
        when(eventSessionRepository.findByIdAndEvent_Id(SESSION_ID, EVENT_ID))
                .thenReturn(Optional.of(session));

        assertThatThrownBy(() -> eventSessionService.deleteSession(EVENT_ID, SESSION_ID))
                .isInstanceOf(ConflictException.class);
        verify(eventSessionRepository, never()).delete(any());
    }

    @Test
    void deleteSession_unlocked_deletes() {
        Event event = buildEvent(EventStatus.DRAFT);
        Instant now = Instant.now();
        EventSession session = buildSession(event, EventSessionStatus.SCHEDULED,
                now.plusSeconds(86400), now.plusSeconds(86400 + 7200));
        session.setSaleStartsAt(now.plusSeconds(3600));
        when(eventSessionRepository.findByIdAndEvent_Id(SESSION_ID, EVENT_ID))
                .thenReturn(Optional.of(session));

        eventSessionService.deleteSession(EVENT_ID, SESSION_ID);

        verify(eventSessionRepository).delete(session);
    }

    @Test
    void listSessionsForCustomer_draftEvent_rejects404() {
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(buildEvent(EventStatus.DRAFT)));

        assertThatThrownBy(() -> eventSessionService.listSessionsForCustomer(EVENT_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listSessionsForCustomer_returnsOnlyFutureSessions() {
        Instant now = Instant.now();
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(buildEvent(EventStatus.PUBLISHED)));
        EventSessionResponse future = dummyResponse(UUID.randomUUID());
        EventSession futureEntity = buildSession(buildEvent(EventStatus.PUBLISHED),
                EventSessionStatus.SCHEDULED, now.plusSeconds(86400), now.plusSeconds(86400 + 7200));
        EventSession startedEntity = buildSession(buildEvent(EventStatus.PUBLISHED),
                EventSessionStatus.SCHEDULED, now.minusSeconds(60), now.plusSeconds(3600));
        when(eventSessionRepository.findByEvent_IdAndStatusAndEndsAtAfterOrderByStartsAtAscIdAsc(
                any(UUID.class), any(EventSessionStatus.class), any(Instant.class)))
                .thenReturn(List.of(futureEntity, startedEntity));
        when(eventSessionMapper.toResponse(futureEntity)).thenReturn(future);

        List<EventSessionResponse> result = eventSessionService.listSessionsForCustomer(EVENT_ID);

        assertThat(result).containsExactly(future);
    }

    @Test
    void getBookingContext_derivesParentEventServerSide() {
        Event event = buildEvent(EventStatus.PUBLISHED);
        Instant now = Instant.now();
        EventSession session = buildSession(event, EventSessionStatus.SCHEDULED,
                now.plusSeconds(86400), now.plusSeconds(86400 + 7200));
        SessionBookingContextResponse context = new SessionBookingContextResponse(
                SESSION_ID, EVENT_ID, EventStatus.PUBLISHED, EventSessionStatus.SCHEDULED,
                session.getStartsAt(), session.getEndsAt(), null, null, event.getVenueId());
        when(eventSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(eventSessionMapper.toBookingContext(session)).thenReturn(context);

        SessionBookingContextResponse result = eventSessionService.getBookingContext(SESSION_ID);

        assertThat(result.eventSessionId()).isEqualTo(SESSION_ID);
        assertThat(result.eventId()).isEqualTo(EVENT_ID);
        assertThat(result.startsAt()).isEqualTo(session.getStartsAt());
        assertThat(result.venueId()).isEqualTo(event.getVenueId());
        verify(eventSessionMapper).toBookingContext(session);
    }

    @Test
    void getBookingContext_unknownSession_rejects404() {
        when(eventSessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventSessionService.getBookingContext(SESSION_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
