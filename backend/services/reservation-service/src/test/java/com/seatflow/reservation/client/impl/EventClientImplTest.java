package com.seatflow.reservation.client.impl;

import com.seatflow.reservation.client.dto.EventPricingDetails;
import com.seatflow.reservation.client.dto.EventSeatMapClientResponse;
import com.seatflow.reservation.client.dto.PricingTierClientDto;
import com.seatflow.reservation.client.dto.SeatMapSectionClientDto;
import com.seatflow.reservation.client.dto.SeatMapSeatClientDto;
import com.seatflow.reservation.client.dto.SessionBookingContextDto;
import com.seatflow.reservation.client.exception.EventClientUnavailableException;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ValidationException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventClientImplTest {

    private final UUID eventId = UUID.randomUUID();
    private final String serviceId = "event-service";

    private RestClient restClient;
    private RestClient.RequestHeadersUriSpec requestSpec;
    private RestClient.RequestHeadersSpec headersSpec;
    private RestClient.ResponseSpec responseSpec;
    private EventClientImpl eventClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        restClient = mock(RestClient.class);
        requestSpec = mock(RestClient.RequestHeadersUriSpec.class);
        headersSpec = mock(RestClient.RequestHeadersSpec.class);
        responseSpec = mock(RestClient.ResponseSpec.class);

        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.requestFactory(any())).thenReturn(builder);
        when(builder.requestInterceptor(any())).thenReturn(builder);
        when(builder.build()).thenReturn(restClient);

        when(restClient.get()).thenReturn(requestSpec);
        when(requestSpec.uri(anyString())).thenReturn(headersSpec);
        when(requestSpec.uri(anyString(), (Object) any())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        eventClient = new EventClientImpl(builder, registry, serviceId);
    }

    // P12-007: builders mirror the real server wire shape — venue layout +
    // pricing only, with NO eventDate (removed from EventSeatMapResponse).
    private EventSeatMapClientResponse buildSeatMapResponse(String status, UUID seatId, BigDecimal price) {
        UUID sectionId = UUID.randomUUID();
        PricingTierClientDto tier = new PricingTierClientDto(UUID.randomUUID(), sectionId, "Standard", price, "USD");
        SeatMapSeatClientDto seat = new SeatMapSeatClientDto(seatId, "A", 1, 0, 0, true);
        SeatMapSectionClientDto section = new SeatMapSectionClientDto(sectionId, "SEC-A", 1, 1, List.of(seat), List.of(tier));
        return new EventSeatMapClientResponse(
                eventId, UUID.randomUUID(), "Concert", status, "Grand Arena", 1000, 1L, List.of(section));
    }

    private EventSeatMapClientResponse buildSeatMapResponse(String status,
                                                             List<UUID> seatIds, BigDecimal price) {
        UUID sectionId = UUID.randomUUID();
        PricingTierClientDto tier = new PricingTierClientDto(UUID.randomUUID(), sectionId, "Standard", price, "USD");
        List<SeatMapSeatClientDto> seats = seatIds.stream()
                .map(seatId -> new SeatMapSeatClientDto(seatId, "A", seatIds.indexOf(seatId) + 1, 0, 0, true))
                .toList();
        SeatMapSectionClientDto section = new SeatMapSectionClientDto(sectionId, "SEC-A", 1, seats.size(), seats, List.of(tier));
        return new EventSeatMapClientResponse(
                eventId, UUID.randomUUID(), "Concert", status, "Grand Arena", 1000, 1L, List.of(section));
    }

    private void stubBody(EventSeatMapClientResponse body) {
        when(responseSpec.body(EventSeatMapClientResponse.class)).thenReturn(body);
    }

    @Test
    void getEventSeatPricingReturnsAuthoritativePricesForPublishedEvent() {
        UUID seatId = UUID.randomUUID();
        BigDecimal price = new BigDecimal("50.00");
        stubBody(buildSeatMapResponse("PUBLISHED", seatId, price));

        EventPricingDetails details = eventClient.getEventSeatPricing(eventId, Set.of(seatId));

        assertThat(details.eventId()).isEqualTo(eventId);
        assertThat(details.eventStatus()).isEqualTo("PUBLISHED");
        assertThat(details.seatPrices()).containsEntry(seatId, price);
    }

    @Test
    void getEventSeatPricingOnlyReturnsPricesForRequestedSeats() {
        UUID requestedSeat = UUID.randomUUID();
        UUID unrequestedSeat = UUID.randomUUID();
        BigDecimal price = new BigDecimal("20.00");
        stubBody(buildSeatMapResponse("PUBLISHED",
                List.of(requestedSeat, unrequestedSeat), price));

        EventPricingDetails details = eventClient.getEventSeatPricing(eventId, Set.of(requestedSeat));

        assertThat(details.seatPrices()).containsOnlyKeys(requestedSeat);
        assertThat(details.seatPrices().get(requestedSeat)).isEqualByComparingTo(price);
    }

    @Test
    void getEventSeatPricingRejectsEmptySeatSet() {
        assertThatThrownBy(() -> eventClient.getEventSeatPricing(eventId, Set.of()))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void getEventSeatPricingSucceedsOnRealWireShapeWithoutEventDate() throws Exception {
        // P12-007 wire-contract regression (REV-001): event-service
        // EventSeatMapResponse no longer sends eventDate. A missing JSON
        // property deserializes to null; pricing must still succeed because
        // bookability comes from the trusted session booking context, never
        // from this payload.
        UUID seatId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        String json = """
                {"eventId":"%s","venueId":"%s","eventTitle":"Concert","status":"PUBLISHED",
                 "venueName":"Grand Arena","venueCapacity":1000,"totalConfiguredSeats":1,
                 "sections":[{"sectionId":"%s","name":"SEC-A","rowCount":1,"colCount":1,
                   "seats":[{"seatId":"%s","rowLabel":"A","seatNumber":1,"gridX":0,"gridY":0,"isActive":true}],
                   "pricingTiers":[{"id":"%s","sectionId":"%s","categoryName":"Standard","price":50.00,"currency":"USD"}]}]}
                """.formatted(eventId, UUID.randomUUID(), sectionId, seatId, tierId, sectionId);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        stubBody(mapper.readValue(json, EventSeatMapClientResponse.class));

        EventPricingDetails details = eventClient.getEventSeatPricing(eventId, Set.of(seatId));

        assertThat(details.eventId()).isEqualTo(eventId);
        assertThat(details.seatPrices()).containsKey(seatId);
        assertThat(details.seatPrices().get(seatId)).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    // P12-007 (REV-001): the event-level temporal gate is removed on purpose.
    // Past/too-close-to-start enforcement lives in
    // ReservationServiceImpl.validateBookingContext against the trusted
    // session booking context (session status, sale windows, startsAt); the
    // seat-map payload carries no instant to gate on.
    @Test
    void getEventSeatPricingRejectsUnpublishedEvent() {
        UUID seatId = UUID.randomUUID();
        stubBody(buildSeatMapResponse("DRAFT", seatId, new BigDecimal("50.00")));

        assertThatThrownBy(() -> eventClient.getEventSeatPricing(eventId, Set.of(seatId)))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void getEventSeatPricingRejectsUnknownSeat() {
        UUID requestedSeat = UUID.randomUUID();
        UUID mappedSeat = UUID.randomUUID();
        stubBody(buildSeatMapResponse("PUBLISHED", mappedSeat, new BigDecimal("50.00")));

        assertThatThrownBy(() -> eventClient.getEventSeatPricing(eventId, Set.of(requestedSeat)))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void getSessionBookingContextReturnsTrustedContext() {
        UUID sessionId = UUID.randomUUID();
        SessionBookingContextDto context = new SessionBookingContextDto(
                sessionId, eventId, "PUBLISHED", "SCHEDULED",
                Instant.now().plusSeconds(86400), Instant.now().plusSeconds(90000),
                null, null, UUID.randomUUID());
        when(responseSpec.body(SessionBookingContextDto.class)).thenReturn(context);

        SessionBookingContextDto result = eventClient.getSessionBookingContext(sessionId);

        assertThat(result.eventSessionId()).isEqualTo(sessionId);
        assertThat(result.eventId()).isEqualTo(eventId);
        assertThat(result.sessionStatus()).isEqualTo("SCHEDULED");
    }

    @Test
    void getSessionBookingContextRejectsNullSessionId() {
        assertThatThrownBy(() -> eventClient.getSessionBookingContext(null))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void getSessionBookingContextMapsNotFoundToValidationException() {
        when(headersSpec.retrieve())
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        assertThatThrownBy(() -> eventClient.getSessionBookingContext(UUID.randomUUID()))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void getSessionBookingContextRejectsMismatchedSession() {
        UUID requested = UUID.randomUUID();
        SessionBookingContextDto context = new SessionBookingContextDto(
                UUID.randomUUID(), eventId, "PUBLISHED", "SCHEDULED",
                Instant.now().plusSeconds(86400), Instant.now().plusSeconds(90000),
                null, null, UUID.randomUUID());
        when(responseSpec.body(SessionBookingContextDto.class)).thenReturn(context);

        assertThatThrownBy(() -> eventClient.getSessionBookingContext(requested))
                .isInstanceOf(EventClientUnavailableException.class);
    }

    @Test
    void getSessionBookingContextRejectsIncompleteResponse() {
        UUID sessionId = UUID.randomUUID();
        when(responseSpec.body(SessionBookingContextDto.class))
                .thenReturn(new SessionBookingContextDto(sessionId, null, "PUBLISHED", "SCHEDULED",
                        Instant.now().plusSeconds(86400), null, null, null, null));

        assertThatThrownBy(() -> eventClient.getSessionBookingContext(sessionId))
                .isInstanceOf(EventClientUnavailableException.class);
    }

    @Test
    void getEventSeatPricingPropagatesNotFoundFromDownstream() {
        when(headersSpec.retrieve())
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        assertThatThrownBy(() -> eventClient.getEventSeatPricing(eventId, Set.of(UUID.randomUUID())))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    void getEventSeatPricingPropagatesServerErrorFromDownstream() {
        when(headersSpec.retrieve())
                .thenThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Server Error", null, null, null));

        assertThatThrownBy(() -> eventClient.getEventSeatPricing(eventId, Set.of(UUID.randomUUID())))
                .isInstanceOf(HttpServerErrorException.class);
    }
}
