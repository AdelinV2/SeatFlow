package com.seatflow.reservation.client.impl;

import com.seatflow.reservation.client.dto.EventPricingDetails;
import com.seatflow.reservation.client.dto.EventSeatMapClientResponse;
import com.seatflow.reservation.client.dto.PricingTierClientDto;
import com.seatflow.reservation.client.dto.SeatMapSectionClientDto;
import com.seatflow.reservation.client.dto.SeatMapSeatClientDto;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ValidationException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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

    private EventSeatMapClientResponse buildSeatMapResponse(String status, Instant eventDate, UUID seatId, BigDecimal price) {
        UUID sectionId = UUID.randomUUID();
        PricingTierClientDto tier = new PricingTierClientDto(UUID.randomUUID(), sectionId, "Standard", price, "USD");
        SeatMapSeatClientDto seat = new SeatMapSeatClientDto(seatId, "A", 1, 0, 0, true);
        SeatMapSectionClientDto section = new SeatMapSectionClientDto(sectionId, "SEC-A", 1, 1, List.of(seat), List.of(tier));
        return new EventSeatMapClientResponse(
                eventId, UUID.randomUUID(), "Concert", status, eventDate, "Grand Arena", 1000, 1L, List.of(section));
    }

    private void stubBody(EventSeatMapClientResponse body) {
        when(responseSpec.body(EventSeatMapClientResponse.class)).thenReturn(body);
    }

    @Test
    void getEventSeatPricingReturnsAuthoritativePricesForPublishedEvent() {
        UUID seatId = UUID.randomUUID();
        BigDecimal price = new BigDecimal("50.00");
        stubBody(buildSeatMapResponse("PUBLISHED", Instant.now().plusSeconds(3600), seatId, price));

        EventPricingDetails details = eventClient.getEventSeatPricing(eventId, Set.of(seatId));

        assertThat(details.eventId()).isEqualTo(eventId);
        assertThat(details.eventStatus()).isEqualTo("PUBLISHED");
        assertThat(details.seatPrices()).containsEntry(seatId, price);
    }

    @Test
    void getEventSeatPricingRejectsEmptySeatSet() {
        assertThatThrownBy(() -> eventClient.getEventSeatPricing(eventId, Set.of()))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void getEventSeatPricingRejectsPastEvent() {
        UUID seatId = UUID.randomUUID();
        stubBody(buildSeatMapResponse("PUBLISHED", Instant.now().minusSeconds(60), seatId, new BigDecimal("50.00")));

        assertThatThrownBy(() -> eventClient.getEventSeatPricing(eventId, Set.of(seatId)))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void getEventSeatPricingRejectsUnpublishedEvent() {
        UUID seatId = UUID.randomUUID();
        stubBody(buildSeatMapResponse("DRAFT", Instant.now().plusSeconds(3600), seatId, new BigDecimal("50.00")));

        assertThatThrownBy(() -> eventClient.getEventSeatPricing(eventId, Set.of(seatId)))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void getEventSeatPricingRejectsUnknownSeat() {
        UUID requestedSeat = UUID.randomUUID();
        UUID mappedSeat = UUID.randomUUID();
        stubBody(buildSeatMapResponse("PUBLISHED", Instant.now().plusSeconds(3600), mappedSeat, new BigDecimal("50.00")));

        assertThatThrownBy(() -> eventClient.getEventSeatPricing(eventId, Set.of(requestedSeat)))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
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
