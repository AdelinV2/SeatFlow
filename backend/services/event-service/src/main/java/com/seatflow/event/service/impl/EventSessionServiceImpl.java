package com.seatflow.event.service.impl;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.event.mapper.EventSessionMapper;
import com.seatflow.event.model.entity.Event;
import com.seatflow.event.model.entity.EventSession;
import com.seatflow.event.model.enums.EventSessionStatus;
import com.seatflow.event.model.enums.EventStatus;
import com.seatflow.event.repository.EventRepository;
import com.seatflow.event.repository.EventSessionRepository;
import com.seatflow.event.service.EventSessionService;
import com.seatflow.event.web.dto.request.CreateEventSessionRequest;
import com.seatflow.event.web.dto.request.UpdateEventSessionRequest;
import com.seatflow.event.web.dto.response.EventSessionResponse;
import com.seatflow.event.web.dto.response.SessionBookingContextResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventSessionServiceImpl implements EventSessionService {

    private final EventRepository eventRepository;
    private final EventSessionRepository eventSessionRepository;
    private final EventSessionMapper eventSessionMapper;

    @Override
    @Transactional
    public EventSessionResponse createSession(UUID eventId, CreateEventSessionRequest request) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));
        rejectTerminalEvent(event);
        validateSchedule(request.startsAt(), request.endsAt(),
                request.saleStartsAt(), request.saleEndsAt(), request.timezone());

        EventSession session = eventSessionMapper.toEntity(request);
        session.setEvent(event);
        session.setStatus(EventSessionStatus.SCHEDULED);
        session.setLegacyBackfill(Boolean.FALSE);
        EventSession saved = eventSessionRepository.save(session);
        log.info("Event session created. eventId={}, sessionId={}, startsAt={}, endsAt={}",
                eventId, saved.getId(), saved.getStartsAt(), saved.getEndsAt());
        return eventSessionMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventSessionResponse> listSessionsForAdmin(UUID eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new ResourceNotFoundException("Event", eventId);
        }
        return eventSessionRepository.findByEvent_IdOrderByStartsAtAscIdAsc(eventId).stream()
                .map(eventSessionMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventSessionResponse> listSessionsForCustomer(UUID eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new ResourceNotFoundException("Event", eventId);
        }
        Instant now = Instant.now();
        return eventSessionRepository
                .findByEvent_IdAndStatusAndEndsAtAfterOrderByStartsAtAscIdAsc(
                        eventId, EventSessionStatus.SCHEDULED, now).stream()
                .filter(session -> session.getStartsAt().isAfter(now))
                .map(eventSessionMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public EventSessionResponse updateSession(UUID eventId, UUID sessionId, UpdateEventSessionRequest request) {
        EventSession session = eventSessionRepository.findByIdAndEvent_Id(sessionId, eventId)
                .orElseThrow(() -> new ResourceNotFoundException("EventSession", sessionId));
        Event event = session.getEvent();
        rejectTerminalEvent(event);
        rejectLockedMutation(event, session, "update");
        validateSchedule(request.startsAt(), request.endsAt(),
                request.saleStartsAt(), request.saleEndsAt(), request.timezone());

        session.setStartsAt(request.startsAt());
        session.setEndsAt(request.endsAt());
        session.setSaleStartsAt(request.saleStartsAt());
        session.setSaleEndsAt(request.saleEndsAt());
        session.setTimezone(request.timezone());
        session.setUpdatedAt(Instant.now());
        EventSession saved = eventSessionRepository.save(session);
        log.info("Event session updated. eventId={}, sessionId={}, startsAt={}, endsAt={}",
                eventId, saved.getId(), saved.getStartsAt(), saved.getEndsAt());
        return eventSessionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteSession(UUID eventId, UUID sessionId) {
        EventSession session = eventSessionRepository.findByIdAndEvent_Id(sessionId, eventId)
                .orElseThrow(() -> new ResourceNotFoundException("EventSession", sessionId));
        Event event = session.getEvent();
        rejectTerminalEvent(event);
        rejectLockedMutation(event, session, "delete");
        eventSessionRepository.delete(session);
        log.info("Event session deleted. eventId={}, sessionId={}", eventId, sessionId);
    }

    @Override
    @Transactional(readOnly = true)
    public SessionBookingContextResponse getBookingContext(UUID sessionId) {
        EventSession session = eventSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("EventSession", sessionId));
        return eventSessionMapper.toBookingContext(session);
    }

    private void rejectTerminalEvent(Event event) {
        if (event.getStatus() == EventStatus.CANCELLED || event.getStatus() == EventStatus.COMPLETED) {
            throw new ValidationException("Event sessions are immutable once the parent event is terminal",
                    ErrorCode.INVALID_REQUEST);
        }
    }

    private void rejectLockedMutation(Event event, EventSession session, String operation) {
        if (isLocked(event, session, Instant.now())) {
            log.warn("Rejected {} of sales-locked session. eventId={}, sessionId={}, sessionStatus={}, saleStartsAt={}",
                    operation, event.getId(), session.getId(), session.getStatus(), session.getSaleStartsAt());
            throw new ConflictException(
                    "Session schedule is locked once sales are open and cannot be " + operation + "d",
                    ErrorCode.CONFLICT);
        }
    }

    /**
     * Effective sales-open boundary used as the local mutation lock.
     *
     * <p>A session is locked when it is no longer {@code SCHEDULED}, when it has
     * already ended, when an explicit {@code saleStartsAt} has passed, or — for a
     * published event — when there is no future {@code saleStartsAt} (the session
     * is then immediately booking-eligible). This conservative rule avoids needing
     * reservation-service state inside event-service.
     */
    static boolean isLocked(Event event, EventSession session, Instant now) {
        if (session.getStatus() != EventSessionStatus.SCHEDULED) {
            return true;
        }
        if (!session.getEndsAt().isAfter(now)) {
            return true;
        }
        if (session.getSaleStartsAt() != null && !session.getSaleStartsAt().isAfter(now)) {
            return true;
        }
        return event.getStatus() == EventStatus.PUBLISHED
                && (session.getSaleStartsAt() == null || !session.getSaleStartsAt().isAfter(now));
    }

    private void validateSchedule(Instant startsAt, Instant endsAt,
                                  Instant saleStartsAt, Instant saleEndsAt, String timezone) {
        if (startsAt == null || endsAt == null) {
            throw new ValidationException("Session start and end times are required", ErrorCode.INVALID_REQUEST);
        }
        if (!startsAt.isBefore(endsAt)) {
            throw new ValidationException("Session start time must be before end time", ErrorCode.INVALID_REQUEST);
        }
        if (saleStartsAt != null && saleEndsAt != null && !saleStartsAt.isBefore(saleEndsAt)) {
            throw new ValidationException("Sale start time must be before sale end time", ErrorCode.INVALID_REQUEST);
        }
        if (saleEndsAt != null && saleEndsAt.isAfter(startsAt)) {
            throw new ValidationException("Sale end time must be on or before session start", ErrorCode.INVALID_REQUEST);
        }
        if (saleStartsAt != null && saleStartsAt.isAfter(startsAt)) {
            throw new ValidationException("Sale start time must be on or before session start", ErrorCode.INVALID_REQUEST);
        }
        if (timezone != null && !timezone.isBlank()) {
            try {
                ZoneId.of(timezone);
            } catch (Exception ex) {
                throw new ValidationException("Timezone must be a valid IANA ZoneId", ErrorCode.INVALID_REQUEST);
            }
        }
    }
}
