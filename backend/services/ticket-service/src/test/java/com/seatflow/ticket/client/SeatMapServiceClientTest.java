package com.seatflow.ticket.client.impl;

import com.seatflow.ticket.client.dto.VenueClientResponse;
import com.seatflow.ticket.client.dto.VenueSeatMapLayoutClientResponse;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeatMapServiceClientTest {

    private final UUID venueId = UUID.randomUUID();
    private final String baseUrl = "http://seat-map-service";

    private RestClient restClient;
    private RestClient.RequestHeadersUriSpec requestSpec;
    private RestClient.RequestHeadersSpec headersSpec;
    private RestClient.ResponseSpec responseSpec;
    private SeatMapServiceClientImpl client;

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
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), errorHandlerCaptor.capture())).thenReturn(responseSpec);
        when(responseSpec.body(VenueClientResponse.class)).thenReturn(
                new VenueClientResponse(venueId, "Sky Arena", "Main St", "Berlin", "DE", 5000));
        when(responseSpec.body(VenueSeatMapLayoutClientResponse.class)).thenReturn(
                new VenueSeatMapLayoutClientResponse(venueId, "Sky Arena", List.of()));

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        client = new SeatMapServiceClientImpl(builder, registry, baseUrl);
    }

    @Test
    void getVenueByIdReturnsDetailsOnSuccess() {
        Optional<VenueClientResponse> response = client.getVenueById(venueId);

        assertThat(response).isPresent();
        assertThat(response.get().id()).isEqualTo(venueId);
        assertThat(response.get().name()).isEqualTo("Sky Arena");
    }

    @Test
    void getVenueLayoutReturnsDetailsOnSuccess() {
        Optional<VenueSeatMapLayoutClientResponse> response = client.getVenueLayout(venueId);

        assertThat(response).isPresent();
        assertThat(response.get().venueId()).isEqualTo(venueId);
    }

    @Test
    void getVenueByIdReturnsEmptyOnRemoteError() {
        when(responseSpec.body(VenueClientResponse.class)).thenThrow(new InterServiceClientException("boom"));

        assertThat(client.getVenueById(venueId)).isEmpty();
    }

    @Test
    void getVenueByIdReturnsEmptyWhenCircuitOpen() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        SeatMapServiceClientImpl openClient = new SeatMapServiceClientImpl(mock(RestClient.Builder.class), registry, baseUrl);
        registry.circuitBreaker("seatMapService").transitionToOpenState();

        assertThat(openClient.getVenueById(venueId)).isEmpty();
    }

    @Test
    void errorHandlerThrowsInterServiceClientException() throws IOException {
        client.getVenueById(venueId);

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
