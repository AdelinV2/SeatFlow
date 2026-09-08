package com.seatflow.gateway;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI route coverage (TASK-P15-007 section 11/acceptance): the narrow AI surface reaches
 * {@code ai-service} through the gateway, write-rate-limited routes do not swallow AI traffic,
 * and normal booking routes are unaffected by the AI surface.
 */
@SpringBootTest(properties = "eureka.client.enabled=false")
class AiRouteConfigurationTest {

    private final RouteLocator routeLocator;

    @Autowired
    AiRouteConfigurationTest(RouteLocator routeLocator) {
        this.routeLocator = routeLocator;
    }

    @Test
    @DisplayName("AI chat/status/conversation/confirm paths route to ai-service")
    void aiSurfaceRoutesToAiService() {
        Map<String, URI> routeUriMap = routes().stream()
                .collect(Collectors.toMap(Route::getId, Route::getUri));
        assertThat(routeUriMap).containsEntry("ai-service", URI.create("lb://ai-service"));

        assertRouteMatches("ai-chat-rate-limited", "/api/ai/chat", HttpMethod.POST);
        assertRouteMatches("ai-service", "/api/ai/status", HttpMethod.GET);
        assertRouteMatches("ai-service", "/api/ai/conversations/123e4567-e89b-12d3-a456-426614174000",
                HttpMethod.DELETE);
        assertRouteMatches("ai-confirm-rate-limited",
                "/api/ai/proposals/123e4567-e89b-12d3-a456-426614174000/confirm", HttpMethod.POST);

        assertThat(routes().stream().map(Route::getId).toList())
                .containsSubsequence("ai-chat-rate-limited", "ai-confirm-rate-limited", "ai-service");
    }

    @Test
    @DisplayName("Rate-limited write routes do not swallow AI traffic and keep their order")
    void rateLimitedWritesDoNotSwallowAi() {
        // AI paths must not match unrelated reservation/payment rate-limited predicates.
        assertRouteMismatches("reservation-create-rate-limited", "/api/ai/chat", HttpMethod.POST);
        assertRouteMismatches("payment-intent-rate-limited",
                "/api/ai/proposals/abc/confirm", HttpMethod.POST);

        assertRouteMatches("ai-chat-rate-limited", "/api/ai/chat", HttpMethod.POST);
        assertRouteMatches("ai-confirm-rate-limited", "/api/ai/proposals/abc/confirm", HttpMethod.POST);

        // Normal write protection is unchanged.
        assertRouteMatches("reservation-create-rate-limited", "/api/reservations", HttpMethod.POST);
        assertRouteMatches("payment-intent-rate-limited", "/api/payments/intent", HttpMethod.POST);
    }

    private List<Route> routes() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertThat(routes).isNotNull();
        return routes;
    }

    private Route route(String routeId) {
        return routes().stream()
                .filter(r -> r.getId().equals(routeId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Route not found: " + routeId));
    }

    private boolean matches(Route route, String path, HttpMethod method) {
        MockServerHttpRequest request = MockServerHttpRequest.method(method, path).build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        return Boolean.TRUE.equals(
                reactor.core.publisher.Mono.from(route.getPredicate().apply(exchange)).block());
    }

    private void assertRouteMatches(String expectedRouteId, String path, HttpMethod method) {
        assertThat(matches(route(expectedRouteId), path, method))
                .withFailMessage("Expected route %s to match %s %s", expectedRouteId, method, path)
                .isTrue();
    }

    private void assertRouteMismatches(String routeId, String path, HttpMethod method) {
        assertThat(matches(route(routeId), path, method))
                .withFailMessage("Route %s must not match %s %s", routeId, method, path)
                .isFalse();
    }
}
