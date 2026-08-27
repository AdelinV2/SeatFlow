package com.seatflow.realtime.config;

import com.seatflow.realtime.security.StompAuthChannelInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Value("${seatflow.cors.allowed-origins:http://localhost:4200}")
    private String allowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // In-memory message broker destination prefix for topic subscriptions (e.g. /topic/events/{eventId}/seats)
        registry.enableSimpleBroker("/topic");

        // Application prefix for messages routed to @MessageMapping methods
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        log.info("Registering STOMP endpoints on /ws with allowed origins: {}", Arrays.toString(origins));

        // 1. SockJS fallback endpoint for Angular client browser connections
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins)
                .withSockJS();

        // 2. Direct WebSocket endpoint for non-SockJS STOMP clients and testing harnesses
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Register the authentication interceptor on the inbound client message channel
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
