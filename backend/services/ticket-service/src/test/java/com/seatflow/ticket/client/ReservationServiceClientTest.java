package com.seatflow.ticket.client.impl;

import com.seatflow.ticket.client.dto.ReservationClientResponse;
import com.seatflow.ticket.client.exception.InterServiceClientException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReservationServiceClientTest {

    private final UUID reservationId = UUID.randomUUID();
    private final String baseUrl = "http://reservation-service";

    private RestClient restClient;
    private RestClient.RequestHeadersUriSpec requestSpec;
    private RestClient.RequestHeadersSpec headersSpec;
    private RestClient.ResponseSpec responseSpec;
    private ReservationServiceClientImpl client;

    private final ArgumentCaptor<RestClient.ResponseSpec.ErrorHandler> errorHandlerCaptor =
            ArgumentCaptor.forClass(RestClient.ResponseSpec.ErrorHandler.class);

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
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), errorHandlerCaptor.capture())).thenReturn(responseSpec);
        when(responseSpec.body(ReservationClientResponse.class)).thenReturn(
                new ReservationClientResponse(reservationId, UUID.randomUUID(), UUID.randomUUID(),
                        "cust@example.com", "CONFIRMED", new BigDecimal("100.00"), 2,
                        List.of(), Instant.now(), Instant.now()));

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        client = new ReservationServiceClientImpl(builder, registry, baseUrl);
    }

    @Test
    void getReservationByIdReturnsDetailsOnSuccess() {
        Optional<ReservationClientResponse> response = client.getReservationById(reservationId);

        assertThat(response).isPresent();
        assertThat(response.get().id()).isEqualTo(reservationId);
        assertThat(response.get().status()).isEqualTo("CONFIRMED");
    }

    @Test
    void getReservationByIdReturnsEmptyOnRemoteError() {
        when(responseSpec.body(ReservationClientResponse.class)).thenThrow(new InterServiceClientException("boom"));

        assertThat(client.getReservationById(reservationId)).isEmpty();
    }

    @Test
    void getReservationByIdReturnsEmptyWhenCircuitOpen() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        ReservationServiceClientImpl openClient = new ReservationServiceClientImpl(mock(RestClient.Builder.class), registry, baseUrl);
        registry.circuitBreaker("reservationService").transitionToOpenState();

        assertThat(openClient.getReservationById(reservationId)).isEmpty();
    }

    @Test
    void errorHandlerThrowsInterServiceClientException() throws IOException {
        client.getReservationById(reservationId);

        ClientHttpResponse res = mock(ClientHttpResponse.class);
        ClientHttpRequest req = mock(ClientHttpRequest.class);

        try {
            errorHandlerCaptor.getAllValues().get(0).handle(req, res);
        } catch (InterServiceClientException e) {
            assertThat(e).isInstanceOf(InterServiceClientException.class);
            return;
        }
        throw new AssertionError("Expected InterServiceClientException from error handler");
    }
}
