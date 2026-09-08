package com.seatflow.ai.client.impl;

import com.seatflow.ai.client.EventServiceClient;
import com.seatflow.ai.client.dto.SeatMapClientDto;
import com.seatflow.ai.client.dto.SessionBookingContextClientDto;
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
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EventServiceClientSeatContextTest {

    private static final AiRequestContext CONTEXT =
            new AiRequestContext("caller-jwt-token", "corr-123", "user-1");

    private MockRestServiceServer server;
    private EventServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        CircuitBreaker breaker = CircuitBreakerRegistry.ofDefaults().circuitBreaker("eventService");
        RestClient restClient = builder.baseUrl("http://event-service").build();
        client = new EventServiceClientImpl(restClient, breaker, "http://event-service");
    }

    @AfterEach
    void verifyServer() {
        server.verify();
    }

    @Test
    @DisplayName("booking context resolves the parent event with propagated identity")
    void bookingContextResolvesParentEvent() {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        server.expect(requestTo("http://event-service/internal/event-sessions/" + sessionId + "/booking-context"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer caller-jwt-token"))
                .andExpect(header("X-Correlation-Id", "corr-123"))
                .andRespond(withSuccess("""
                        {"eventSessionId":"%s","eventId":"%s",\
                        "eventStatus":"PUBLISHED","sessionStatus":"SCHEDULED",\
                        "startsAt":"2026-10-05T19:00:00Z","endsAt":"2026-10-05T21:30:00Z",\
                        "saleStartsAt":null,"saleEndsAt":null,\
                        "venueId":"%s"}
                        """.formatted(sessionId, eventId, UUID.randomUUID()), MediaType.APPLICATION_JSON));

        SessionBookingContextClientDto context = client.getSessionBookingContext(sessionId, CONTEXT);

        assertThat(context.eventSessionId()).isEqualTo(sessionId);
        assertThat(context.eventId()).isEqualTo(eventId);
        assertThat(context.sessionStatus()).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("unknown session maps to INVALID_TOOL_ARGUMENT")
    void unknownSessionMapsToInvalidArgument() {
        UUID sessionId = UUID.randomUUID();
        server.expect(requestTo(
                        "http://event-service/internal/event-sessions/" + sessionId + "/booking-context"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> client.getSessionBookingContext(sessionId, CONTEXT))
                .isInstanceOf(AiToolException.class)
                .satisfies(ex -> assertThat(((AiToolException) ex).getError())
                        .isEqualTo(AiToolError.INVALID_TOOL_ARGUMENT));
    }

    @Test
    @DisplayName("seat map parses sections, seats, tiers, and layout elements")
    void seatMapParses() {
        UUID eventId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        server.expect(requestTo("http://event-service/api/events/" + eventId + "/seat-map"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Correlation-Id", "corr-123"))
                .andRespond(withSuccess("""
                        {"eventId":"%s","venueId":"%s","eventTitle":"Hamlet",\
                        "status":"PUBLISHED","venueName":"Hall","venueCapacity":100,\
                        "totalConfiguredSeats":1,"layoutVersion":3,\
                        "sections":[{"sectionId":"%s","name":"Stalls",\
                        "rowCount":1,"colCount":10,"isActive":true,\
                        "positionX":0,"positionY":0,"width":440,"height":44,\
                        "rotationDeg":0,"zIndex":0,"shapeMetadata":null,\
                        "seats":[{"seatId":"%s","rowLabel":"A","seatNumber":1,\
                        "gridX":0,"gridY":0,"isActive":true,\
                        "positionX":44,"positionY":0}],\
                        "pricingTiers":[{"id":"%s","sectionId":"%s",\
                        "categoryName":"Standard","price":25.00,"currency":"EUR"}]}],\
                        "layoutElements":[{"elementId":"%s","type":"STAGE","label":"Main",\
                        "geometry":{"x":0,"y":200,"width":440,"height":60,"rotationDeg":0},\
                        "zIndex":0}]}
                        """.formatted(eventId, UUID.randomUUID(), sectionId, seatId, tierId,
                        sectionId, UUID.randomUUID()), MediaType.APPLICATION_JSON));

        SeatMapClientDto seatMap = client.getSeatMap(eventId, CONTEXT);

        assertThat(seatMap.eventId()).isEqualTo(eventId);
        assertThat(seatMap.sections()).hasSize(1);
        assertThat(seatMap.sections().getFirst().seats()).hasSize(1);
        assertThat(seatMap.sections().getFirst().pricingTiers()).hasSize(1);
        assertThat(seatMap.layoutElements()).hasSize(1);
        assertThat(seatMap.layoutElements().getFirst().type()).isEqualTo("STAGE");
    }

    @Test
    @DisplayName("missing seat map maps to NOT_FOUND without a substitute")
    void seatMapNotFound() {
        UUID eventId = UUID.randomUUID();
        server.expect(requestTo("http://event-service/api/events/" + eventId + "/seat-map"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> client.getSeatMap(eventId, CONTEXT))
                .isInstanceOf(AiToolException.class)
                .satisfies(ex -> assertThat(((AiToolException) ex).getError())
                        .isEqualTo(AiToolError.NOT_FOUND));
    }
}
