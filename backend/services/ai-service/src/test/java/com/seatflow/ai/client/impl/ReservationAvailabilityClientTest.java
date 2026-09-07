package com.seatflow.ai.client.impl;

import com.seatflow.ai.client.ReservationAvailabilityClient;
import com.seatflow.ai.client.dto.SeatAvailabilityClientDto;
import com.seatflow.ai.client.exception.ReservationServiceUnavailableException;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ReservationAvailabilityClientTest {

    private static final AiRequestContext CONTEXT =
            new AiRequestContext("caller-jwt-token", "corr-123", "user-1");

    private MockRestServiceServer server;
    private ReservationAvailabilityClient client;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        breaker = CircuitBreakerRegistry.ofDefaults().circuitBreaker("reservationService");
        RestClient restClient = builder.baseUrl("http://reservation-service").build();
        client = new ReservationAvailabilityClientImpl(restClient, breaker, "http://reservation-service");
    }

    @AfterEach
    void verifyServer() {
        server.verify();
    }

    @Test
    @DisplayName("availability propagates Authorization and Correlation headers and parses statuses")
    void availabilityPropagatesSecurityContext() {
        UUID sessionId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        server.expect(requestTo("http://reservation-service/api/event-sessions/" + sessionId + "/seats/availability"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer caller-jwt-token"))
                .andExpect(header("X-Correlation-Id", "corr-123"))
                .andRespond(withSuccess("""
                        {"eventSessionId":"%s","eventId":null,\
                        "seatStatuses":[{"seatId":"%s","status":"HELD"}]}
                        """.formatted(sessionId, seatId), MediaType.APPLICATION_JSON));

        SeatAvailabilityClientDto response = client.getSeatAvailability(sessionId, CONTEXT);

        assertThat(response.eventSessionId()).isEqualTo(sessionId);
        assertThat(response.seatStatuses()).hasSize(1);
        assertThat(response.seatStatuses().getFirst().status()).isEqualTo("HELD");
    }

    @Test
    @DisplayName("unknown session maps to INVALID_TOOL_ARGUMENT, never to invented data")
    void unknownSessionMapsToInvalidArgument() {
        UUID sessionId = UUID.randomUUID();
        server.expect(requestTo(
                        "http://reservation-service/api/event-sessions/" + sessionId + "/seats/availability"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getSeatAvailability(sessionId, CONTEXT))
                .isInstanceOf(AiToolException.class)
                .satisfies(ex -> assertThat(((AiToolException) ex).getError())
                        .isEqualTo(AiToolError.INVALID_TOOL_ARGUMENT));
    }

    @Test
    @DisplayName("5xx maps to DOWNSTREAM_UNAVAILABLE, never to stale local data")
    void serverErrorMapsToUnavailable() {
        UUID sessionId = UUID.randomUUID();
        server.expect(requestTo(
                        "http://reservation-service/api/event-sessions/" + sessionId + "/seats/availability"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getSeatAvailability(sessionId, CONTEXT))
                .isInstanceOf(ReservationServiceUnavailableException.class)
                .satisfies(ex -> assertThat(
                                ((ReservationServiceUnavailableException) ex).getError())
                        .isEqualTo(AiToolError.DOWNSTREAM_UNAVAILABLE));
    }

    @Test
    @DisplayName("read timeout maps to DOWNSTREAM_TIMEOUT")
    void timeoutMapsToTimeoutError() {
        UUID sessionId = UUID.randomUUID();
        server.expect(requestTo(
                        "http://reservation-service/api/event-sessions/" + sessionId + "/seats/availability"))
                .andRespond(request -> {
                    throw new ResourceAccessException("Read timed out",
                            new SocketTimeoutException("Read timed out"));
                });

        assertThatThrownBy(() -> client.getSeatAvailability(sessionId, CONTEXT))
                .isInstanceOf(ReservationServiceUnavailableException.class)
                .satisfies(ex -> assertThat(
                                ((ReservationServiceUnavailableException) ex).getError())
                        .isEqualTo(AiToolError.DOWNSTREAM_TIMEOUT));
    }

    @Test
    @DisplayName("mismatched session payload is rejected instead of trusted")
    void sessionMismatchRejected() {
        UUID requested = UUID.randomUUID();
        UUID returned = UUID.randomUUID();
        server.expect(requestTo(
                        "http://reservation-service/api/event-sessions/" + requested + "/seats/availability"))
                .andRespond(withSuccess("""
                        {"eventSessionId":"%s","eventId":null,"seatStatuses":[]}
                        """.formatted(returned), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getSeatAvailability(requested, CONTEXT))
                .isInstanceOf(ReservationServiceUnavailableException.class)
                .satisfies(ex -> assertThat(
                                ((ReservationServiceUnavailableException) ex).getError())
                        .isEqualTo(AiToolError.UNEXPECTED_TOOL_FAILURE));
    }

    @Test
    @DisplayName("open circuit fails fast without an HTTP call")
    void openCircuitFailsFast() {
        breaker.transitionToOpenState();

        assertThatThrownBy(() -> client.getSeatAvailability(UUID.randomUUID(), CONTEXT))
                .isInstanceOf(ReservationServiceUnavailableException.class)
                .satisfies(ex -> assertThat(
                                ((ReservationServiceUnavailableException) ex).getError())
                        .isEqualTo(AiToolError.DOWNSTREAM_UNAVAILABLE));
    }
}
