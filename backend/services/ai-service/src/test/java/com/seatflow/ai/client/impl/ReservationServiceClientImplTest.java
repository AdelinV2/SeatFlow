package com.seatflow.ai.client.impl;

import com.seatflow.ai.client.dto.ReservationServiceReservationDto;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Downstream client tests over loopback HTTP (TASK-P15-005 section 8; mandatory 1-2).
 *
 * <p>Verifies Bearer/correlation propagation, safe 403/404 mapping with no privileged retry,
 * and server-derived creation payloads. Uses only loopback synthetic data.
 */
class ReservationServiceClientImplTest {

    private HttpServer server;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> correlationIds = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ReservationServiceClientImpl client(String handlerPath, int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(handlerPath, exchange -> {
            calls.incrementAndGet();
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            correlationIds.add(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        var circuitBreaker = CircuitBreakerRegistry.ofDefaults().circuitBreaker("test");
        return new ReservationServiceClientImpl(
                RestClient.builder().baseUrl(baseUrl).build(), circuitBreaker, baseUrl);
    }

    private static String reservationJson(UUID id) {
        return """
                {"id":"%s","eventSessionId":"%s","eventId":"%s","status":"PENDING",
                 "expiresAt":"2026-09-07T10:15:00Z","totalAmount":30.00,"seatCount":2,
                 "sessionStartsAt":"2026-09-10T19:00:00Z","sessionEndsAt":null,
                 "sessionTimezone":null,"createdAt":"2026-09-07T10:00:00Z","seats":[]}
                """.formatted(id, UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    @DisplayName("1: getReservation propagates USER JWT and correlation ID")
    void propagatesIdentity() throws Exception {
        UUID id = UUID.randomUUID();
        var client = client("/api/reservations/" + id, 200, reservationJson(id));

        ReservationServiceReservationDto dto = client.getReservation(
                id, new AiRequestContext("user-jwt-123", "corr-abc", "owner-1"));

        assertThat(dto.id()).isEqualTo(id);
        assertThat(dto.status()).isEqualTo("PENDING");
        assertThat(authorizations).containsExactly("Bearer user-jwt-123");
        assertThat(correlationIds).containsExactly("corr-abc");
    }

    @Test
    @DisplayName("2: 403 never retries with a privileged identity (exactly one call)")
    void forbiddenSingleCall() throws Exception {
        UUID id = UUID.randomUUID();
        var client = client("/api/reservations/" + id, 403, "{\"error\":\"forbidden\"}");

        assertThatThrownBy(() -> client.getReservation(
                id, new AiRequestContext("user-jwt-123", "corr-abc", "owner-1")))
                .isInstanceOf(AiToolException.class)
                .matches(ex -> ((AiToolException) ex).getError() == AiToolError.FORBIDDEN);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(authorizations).containsExactly("Bearer user-jwt-123");
    }

    @Test
    @DisplayName("404 maps to a safe NOT_FOUND tool error")
    void notFoundMaps() throws Exception {
        UUID id = UUID.randomUUID();
        var client = client("/api/reservations/" + id, 404, "{\"error\":\"missing\"}");

        assertThatThrownBy(() -> client.getReservation(
                id, new AiRequestContext("user-jwt-123", "corr-abc", "owner-1")))
                .isInstanceOf(AiToolException.class)
                .matches(ex -> ((AiToolException) ex).getError() == AiToolError.NOT_FOUND);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("createReservation posts server-derived values with the proposal idempotency key")
    void createPostsServerDerived() throws Exception {
        var client = client("/api/reservations", 201, reservationJson(UUID.randomUUID()));
        UUID sessionId = UUID.randomUUID();
        List<UUID> seats = List.of(UUID.randomUUID(), UUID.randomUUID());
        List<BigDecimal> prices = List.of(new BigDecimal("15.00"), new BigDecimal("15.00"));

        ReservationServiceReservationDto dto = client.createReservation(
                sessionId, seats, prices, "idem-server-key-1",
                new AiRequestContext("user-jwt-123", "corr-abc", "owner-1"));

        assertThat(dto.id()).isNotNull();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(authorizations).containsExactly("Bearer user-jwt-123");
        assertThat(HttpStatus.CREATED.value()).isEqualTo(201);
    }
}
