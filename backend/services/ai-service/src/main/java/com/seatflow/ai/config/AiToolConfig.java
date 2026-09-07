package com.seatflow.ai.config;

import com.seatflow.ai.tool.EventDiscoveryTools;
import com.seatflow.ai.tool.SeatAvailabilityTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Registers the TASK-P15-002 read-only domain tools through the Spring AI 2.0.x tool API.
 *
 * <p>The provider exposes exactly the three intended read-only tools. State-changing tools from
 * later Phase 15 tasks must be registered through their own explicit callbacks, never by
 * widening this set implicitly.
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
    public Clock toolClock() {
        return Clock.systemUTC();
    }
}
