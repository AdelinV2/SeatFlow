package com.seatflow.ai.config;

import com.seatflow.ai.tool.EventDiscoveryTools;
import com.seatflow.ai.tool.ReservationLookupTools;
import com.seatflow.ai.tool.SeatAvailabilityTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Registers the TASK-P15-002 read-only domain tools plus the TASK-P15-005 ownership-safe
 * {@code getReservation} lookup through the Spring AI 2.0.x tool API.
 *
 * <p>The provider exposes exactly the six intended read-only tools. State-changing tools
 * ({@code createReservation}) are never registered here: reservation creation is reachable only
 * through the explicit {@code POST /api/ai/proposals/{id}/confirm} endpoint, never through
 * model tool calling.
 */
@Configuration
public class AiToolConfig {

    @Bean
    public ToolCallbackProvider eventDiscoveryToolCallbacks(EventDiscoveryTools eventDiscoveryTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(eventDiscoveryTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider seatAvailabilityToolCallbacks(SeatAvailabilityTools seatAvailabilityTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(seatAvailabilityTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider reservationLookupToolCallbacks(
            ReservationLookupTools reservationLookupTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(reservationLookupTools)
                .build();
    }

    @Bean
    public Clock toolClock() {
        return Clock.systemUTC();
    }
}
