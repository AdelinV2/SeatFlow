package com.seatflow.realtime.config;

import com.seatflow.realtime.security.StompAuthChannelInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketConfigTest {

    @Mock
    private StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Mock
    private MessageBrokerRegistry messageBrokerRegistry;

    @Mock
    private StompEndpointRegistry stompEndpointRegistry;

    @Mock
    private StompWebSocketEndpointRegistration endpointRegistration;

    @Mock
    private ChannelRegistration channelRegistration;

    @Test
    @DisplayName("Should configure message broker prefixes correctly")
    void configureMessageBroker_ConfiguresPrefixes() {
        WebSocketConfig config = new WebSocketConfig(stompAuthChannelInterceptor);

        config.configureMessageBroker(messageBrokerRegistry);

        verify(messageBrokerRegistry).enableSimpleBroker("/topic");
        verify(messageBrokerRegistry).setApplicationDestinationPrefixes("/app");
    }

    @Test
    @DisplayName("Should register /ws endpoints with SockJS fallback and CORS origins")
    void registerStompEndpoints_RegistersEndpointsWithCors() {
        WebSocketConfig config = new WebSocketConfig(stompAuthChannelInterceptor);
        ReflectionTestUtils.setField(config, "allowedOrigins", "http://localhost:4200, https://seatflow.com");

        when(stompEndpointRegistry.addEndpoint("/ws")).thenReturn(endpointRegistration);
        when(endpointRegistration.setAllowedOriginPatterns(any(String[].class))).thenReturn(endpointRegistration);

        config.registerStompEndpoints(stompEndpointRegistry);

        verify(stompEndpointRegistry, times(2)).addEndpoint("/ws");
        verify(endpointRegistration, times(1)).withSockJS();
        verify(endpointRegistration, times(2)).setAllowedOriginPatterns(new String[]{"http://localhost:4200", "https://seatflow.com"});
    }

    @Test
    @DisplayName("Should register StompAuthChannelInterceptor on client inbound channel")
    void configureClientInboundChannel_RegistersInterceptor() {
        WebSocketConfig config = new WebSocketConfig(stompAuthChannelInterceptor);

        config.configureClientInboundChannel(channelRegistration);

        verify(channelRegistration).interceptors(stompAuthChannelInterceptor);
    }
}
