package com.seatflow.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "eureka.client.enabled=false")
class RouteConfigurationTest {

    @Autowired
    private RouteLocator routeLocator;

    @Autowired(required = false)
    private org.springframework.cloud.gateway.config.GatewayProperties gatewayProperties;

    @Autowired(required = false)
    private org.springframework.cloud.gateway.route.RouteDefinitionLocator routeDefinitionLocator;

    @Test
    @DisplayName("Verify all microservice routes are registered in RouteLocator")
    void allExpectedRoutesAreRegistered() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertThat(routes).isNotNull();

        Map<String, URI> routeUriMap = routes.stream()
                .collect(Collectors.toMap(Route::getId, Route::getUri));

        assertThat(routeUriMap)
                .containsEntry("user-service", URI.create("lb://user-service"))
                .containsEntry("seat-map-service", URI.create("lb://seat-map-service"))
                .containsEntry("event-service", URI.create("lb://event-service"))
                .containsEntry("reservation-service", URI.create("lb://reservation-service"))
                .containsEntry("payment-service", URI.create("lb://payment-service"))
                .containsEntry("ticket-service", URI.create("lb://ticket-service"))
                .containsEntry("realtime-service", URI.create("lb://realtime-service"))
                .containsEntry("notification-service", URI.create("lb://notification-service"));
    }

    @Test
    @DisplayName("Verify user-service route predicate matches public and admin endpoints")
    void userServiceRoutePredicateMatches() {
        assertRouteMatches("user-service", "/api/users/me");
        assertRouteMatches("user-service", "/api/admin/users");
    }

    @Test
    @DisplayName("Verify seat-map-service route predicate matches public and admin endpoints")
    void seatMapServiceRoutePredicateMatches() {
        assertRouteMatches("seat-map-service", "/api/venues");
        assertRouteMatches("seat-map-service", "/api/venues/123/layout");
        assertRouteMatches("seat-map-service", "/api/admin/venues");
    }

    @Test
    @DisplayName("Verify event-service route predicate matches public and admin endpoints")
    void eventServiceRoutePredicateMatches() {
        assertRouteMatches("event-service", "/api/events");
        assertRouteMatches("event-service", "/api/events/123");
        assertRouteMatches("event-service", "/api/admin/events");
    }

    @Test
    @DisplayName("Verify reservation-service route predicate matches public and admin endpoints")
    void reservationServiceRoutePredicateMatches() {
        assertRouteMatches("reservation-service", "/api/reservations");
        assertRouteMatches("reservation-service", "/api/reservations/123");
        assertRouteMatches("reservation-service", "/api/admin/reservations");
    }

    @Test
    @DisplayName("Verify payment-service route predicate matches payment endpoints")
    void paymentServiceRoutePredicateMatches() {
        assertRouteMatches("payment-service", "/api/payments/intent");
        assertRouteMatches("payment-service", "/api/payments/123");
        assertRouteMatches("payment-service", "/api/admin/payments");
    }

    @Test
    @DisplayName("Verify ticket-service route predicate matches ticket, scanner, and admin endpoints")
    void ticketServiceRoutePredicateMatches() {
        assertRouteMatches("ticket-service", "/api/tickets/my-tickets");
        assertRouteMatches("ticket-service", "/api/scanner/tickets/validate");
        assertRouteMatches("ticket-service", "/api/admin/tickets");
    }

    @Test
    @DisplayName("Verify realtime-service route predicate matches websocket endpoints")
    void realtimeServiceRoutePredicateMatches() {
        assertRouteMatches("realtime-service", "/ws");
        assertRouteMatches("realtime-service", "/ws/info");
        assertRouteMatches("realtime-service", "/ws/123/xhr_streaming");
    }

    @Test
    @DisplayName("Verify notification-service route predicate matches notification endpoints")
    void notificationServiceRoutePredicateMatches() {
        assertRouteMatches("notification-service", "/api/notifications");
        assertRouteMatches("notification-service", "/api/admin/notifications");
    }

    private void assertRouteMatches(String expectedRouteId, String path) {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertThat(routes).isNotNull();

        Route matchingRoute = routes.stream()
                .filter(r -> r.getId().equals(expectedRouteId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Route not found: " + expectedRouteId));

        MockServerHttpRequest request = MockServerHttpRequest.get(path).build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        boolean predicateMatches = Boolean.TRUE.equals(
                reactor.core.publisher.Mono.from(matchingRoute.getPredicate().apply(exchange)).block()
        );
        assertThat(predicateMatches)
                .withFailMessage("Expected route %s to match path %s, but predicate returned false", expectedRouteId, path)
                .isTrue();
    }
}
