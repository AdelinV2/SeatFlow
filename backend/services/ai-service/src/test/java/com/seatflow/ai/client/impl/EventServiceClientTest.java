package com.seatflow.ai.client.impl;

import com.seatflow.ai.client.EventServiceClient;
import com.seatflow.ai.client.dto.EventDetailClientDto;
import com.seatflow.ai.client.dto.EventSessionClientDto;
import com.seatflow.ai.client.exception.EventServiceNotFoundException;
import com.seatflow.ai.client.exception.EventServiceUnavailableException;
import com.seatflow.ai.client.impl.EventServiceClientImpl;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;
import com.seatflow.common.domain.dto.PagedResult;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EventServiceClientTest {

    private static final AiRequestContext CONTEXT =
            new AiRequestContext("caller-jwt-token", "corr-123", "user-1");

    private MockRestServiceServer server;
    private EventServiceClient client;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        breaker = CircuitBreakerRegistry.ofDefaults().circuitBreaker("eventService");
        RestClient restClient = builder.baseUrl("http://event-service").build();
        client = new EventServiceClientImpl(restClient, breaker, "http://event-service");
    }

    @AfterEach
    void verifyServer() {
        server.verify();
    }

    @Test
    @DisplayName("search propagates Authorization and Correlation headers and parses the page")
    void searchPropagatesSecurityContext() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo("http://event-service/api/events?page=0&size=5&sort=nextSessionStartsAt,asc&search=Hamlet&category=THEATRE"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer caller-jwt-token"))
                .andExpect(header("X-Correlation-Id", "corr-123"))
                .andRespond(withSuccess("""
                        {"content":[{"id":"%s","title":"Hamlet","category":"THEATRE",\
                        "bannerUrl":null,"nextSessionStartsAt":"2026-09-25T19:00:00Z",\
                        "minPrice":10.00,"maxPrice":50.00,"currency":"EUR"}],\
                        "page":0,"size":5,"totalElements":1,"totalPages":1,\
                        "isFirst":true,"isLast":true}
                        """.formatted(id), MediaType.APPLICATION_JSON));

        PagedResult<?> page = client.searchEvents("Hamlet", "THEATRE", 0, 5, CONTEXT);

        assertThat(page.content()).hasSize(1);
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("search without criteria calls the upcoming-events catalog without filters")
    void searchWithoutCriteriaUsesCatalog() {
        server.expect(requestTo("http://event-service/api/events?page=0&size=5&sort=nextSessionStartsAt,asc"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer caller-jwt-token"))
                .andExpect(header("X-Correlation-Id", "corr-123"))
                .andRespond(withSuccess("""
                        {"content":[],"page":0,"size":5,"totalElements":0,"totalPages":0,\
                        "isFirst":true,"isLast":true}
                        """, MediaType.APPLICATION_JSON));

        PagedResult<?> page = client.searchEvents(null, null, 0, 5, CONTEXT);

        assertThat(page.content()).isEmpty();
    }

    @Test
    @DisplayName("getEvent returns the detail snapshot with the exact identifiers")
    void getEventReturnsDetail() {
        UUID eventId = UUID.randomUUID();
        UUID venueId = UUID.randomUUID();
        server.expect(requestTo("http://event-service/api/events/" + eventId))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer caller-jwt-token"))
                .andRespond(withSuccess("""
                        {"id":"%s","venueId":"%s","title":"Hamlet",\
                        "description":"A tragedy.","category":"THEATRE","status":"PUBLISHED",\
                        "pricingTiers":[],"sessions":null,\
                        "createdAt":"2026-09-01T10:00:00Z","updatedAt":"2026-09-02T10:00:00Z"}
                        """.formatted(eventId, venueId), MediaType.APPLICATION_JSON));

        EventDetailClientDto detail = client.getEvent(eventId, CONTEXT);

        assertThat(detail.id()).isEqualTo(eventId);
        assertThat(detail.venueId()).isEqualTo(venueId);
        assertThat(detail.status()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("listSessions returns session-scoped IDs with exact timestamps")
    void listSessionsReturnsSnapshots() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        server.expect(requestTo("http://event-service/api/events/" + eventId + "/sessions"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Correlation-Id", "corr-123"))
                .andRespond(withSuccess("""
                        [{"id":"%s","eventId":"%s",\
                        "startsAt":"2026-10-05T19:00:00Z","endsAt":"2026-10-05T21:30:00Z",\
                        "saleStartsAt":"2026-09-01T00:00:00Z","saleEndsAt":"2026-10-04T00:00:00Z",\
                        "status":"SCHEDULED","timezone":"UTC",\
                        "createdAt":"2026-09-01T10:00:00Z","updatedAt":"2026-09-01T10:00:00Z"}]
                        """.formatted(sessionId, eventId), MediaType.APPLICATION_JSON));

        List<EventSessionClientDto> sessions = client.listSessions(eventId, CONTEXT);

        assertThat(sessions).hasSize(1);
        assertThat(sessions.getFirst().id()).isEqualTo(sessionId);
        assertThat(sessions.getFirst().startsAt().toString()).isEqualTo("2026-10-05T19:00:00Z");
    }

    @Test
    @DisplayName("404 maps to NOT_FOUND without fabricating a substitute")
    void notFoundMapsToTaxonomy() {
        UUID eventId = UUID.randomUUID();
        server.expect(requestTo("http://event-service/api/events/" + eventId))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> client.getEvent(eventId, CONTEXT))
                .isInstanceOf(EventServiceNotFoundException.class)
                .satisfies(ex -> assertThat(((EventServiceNotFoundException) ex).getError())
                        .isEqualTo(AiToolError.NOT_FOUND));
    }

    @Test
    @DisplayName("401/403 map to the stable auth taxonomy")
    void authFailuresMapToTaxonomy() {
        UUID eventId = UUID.randomUUID();
        server.expect(requestTo("http://event-service/api/events/" + eventId))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.getEvent(eventId, CONTEXT))
                .isInstanceOf(AiToolException.class)
                .satisfies(ex -> assertThat(((AiToolException) ex).getError())
                        .isEqualTo(AiToolError.UNAUTHENTICATED));

        server.verify();
        server.reset();
        server.expect(requestTo("http://event-service/api/events/" + eventId))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.getEvent(eventId, CONTEXT))
                .isInstanceOf(AiToolException.class)
                .satisfies(ex -> assertThat(((AiToolException) ex).getError())
                        .isEqualTo(AiToolError.FORBIDDEN));
    }

    @Test
    @DisplayName("5xx maps to DOWNSTREAM_UNAVAILABLE, never to invented data")
    void serverErrorMapsToUnavailable() {
        UUID eventId = UUID.randomUUID();
        server.expect(requestTo("http://event-service/api/events/" + eventId + "/sessions"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.listSessions(eventId, CONTEXT))
                .isInstanceOf(EventServiceUnavailableException.class)
                .satisfies(ex -> assertThat(((EventServiceUnavailableException) ex).getError())
                        .isEqualTo(AiToolError.DOWNSTREAM_UNAVAILABLE));
    }

    @Test
    @DisplayName("open circuit fails fast without an HTTP call")
    void openCircuitFailsFast() {
        breaker.transitionToOpenState();
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> client.getEvent(eventId, CONTEXT))
                .isInstanceOf(EventServiceUnavailableException.class)
                .satisfies(ex -> assertThat(((EventServiceUnavailableException) ex).getError())
                        .isEqualTo(AiToolError.DOWNSTREAM_UNAVAILABLE));
    }

    @Test
    @DisplayName("query params use the existing public catalog semantics only")
    void searchQueryParams() {
        server.expect(requestTo("http://event-service/api/events?page=0&size=5&sort=nextSessionStartsAt,asc"))
                .andExpect(queryParam("page", "0"))
                .andRespond(withSuccess("""
                        {"content":[],"page":0,"size":5,"totalElements":0,"totalPages":0,\
                        "isFirst":true,"isLast":true}
                        """, MediaType.APPLICATION_JSON));

        client.searchEvents(null, null, 0, 5, CONTEXT);
    }
}
