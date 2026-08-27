package com.seatflow.ticket.client.impl;

import com.seatflow.ticket.client.dto.EventClientResponse;
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
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventServiceClientTest {

    private final UUID eventId = UUID.randomUUID();
    private final String baseUrl = "http://event-service";

    private RestClient restClient;
    private RestClient.RequestHeadersUriSpec requestSpec;
    private RestClient.RequestHeadersSpec headersSpec;
    private RestClient.ResponseSpec responseSpec;
    private EventServiceClientImpl client;

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
        when(responseSpec.body(EventClientResponse.class)).thenReturn(
                new EventClientResponse(eventId, UUID.randomUUID(), "Concert", "MUSIC",
                        Instant.now(), "PUBLISHED", "http://banner"));

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        client = new EventServiceClientImpl(builder, registry, baseUrl);
    }

    @Test
    void getEventByIdReturnsDetailsOnSuccess() {
        Optional<EventClientResponse> response = client.getEventById(eventId);

        assertThat(response).isPresent();
        assertThat(response.get().id()).isEqualTo(eventId);
        assertThat(response.get().title()).isEqualTo("Concert");
    }

    @Test
    void getEventByIdReturnsEmptyOnRemoteError() {
        when(responseSpec.body(EventClientResponse.class)).thenThrow(new InterServiceClientException("boom"));

        assertThat(client.getEventById(eventId)).isEmpty();
    }

    @Test
    void getEventByIdReturnsEmptyWhenCircuitOpen() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        EventServiceClientImpl openClient = new EventServiceClientImpl(mock(RestClient.Builder.class), registry, baseUrl);
        registry.circuitBreaker("eventService").transitionToOpenState();

        assertThat(openClient.getEventById(eventId)).isEmpty();
    }

    @Test
    void errorHandlerThrowsInterServiceClientException() throws IOException {
        // Prime the onStatus registration.
        client.getEventById(eventId);

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
