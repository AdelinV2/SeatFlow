package com.seatflow.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Programmatic route configuration using Spring Cloud Gateway Java DSL.
 * Defines explicit load-balanced routes to downstream microservices via Eureka discovery.
 */
@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouteLocator routeLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                // 1. Identity & User Service
                .route("user-service", r -> r
                        .path("/api/users/**", "/api/admin/users/**")
                        .uri("lb://user-service"))

                // 2. Seat Map & Venue Service
                .route("seat-map-service", r -> r
                        .path("/api/venues/**", "/api/admin/venues/**")
                        .uri("lb://seat-map-service"))

                // 3. Event Catalog Service
                .route("event-service", r -> r
                        .path("/api/events/**", "/api/admin/events/**")
                        .uri("lb://event-service"))

                // 4. Reservation Service
                .route("reservation-service", r -> r
                        .path("/api/reservations/**", "/api/admin/reservations/**")
                        .uri("lb://reservation-service"))

                // 5. Payment Service
                .route("payment-service", r -> r
                        .path("/api/payments/**", "/api/admin/payments/**")
                        .uri("lb://payment-service"))

                // 6. Ticket & Scanner Service
                .route("ticket-service", r -> r
                        .path("/api/tickets/**", "/api/scanner/tickets/**", "/api/admin/tickets/**")
                        .uri("lb://ticket-service"))

                // 7. Realtime WebSocket Service (STOMP / SockJS fallback)
                .route("realtime-service", r -> r
                        .path("/ws/**", "/ws")
                        .uri("lb://realtime-service"))

                // 8. Notification Service
                .route("notification-service", r -> r
                        .path("/api/notifications/**", "/api/admin/notifications/**")
                        .uri("lb://notification-service"))

                .build();
    }
}
