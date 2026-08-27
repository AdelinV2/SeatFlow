package com.seatflow.realtime.security;

import com.seatflow.common.security.converter.JwtRoleConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StompAuthChannelInterceptorTest {

    @Mock
    private JwtDecoder jwtDecoder;

    @Mock
    private JwtRoleConverter jwtRoleConverter;

    @Mock
    private MessageChannel messageChannel;

    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthChannelInterceptor(jwtDecoder, jwtRoleConverter);
    }

    @Test
    @DisplayName("Should authenticate user and set JwtAuthenticationToken when valid Bearer token provided")
    void preSend_ValidBearerToken_AuthenticatesUser() {
        String rawToken = "valid.jwt.token";
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        accessor.addNativeHeader(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Jwt jwt = new Jwt(
                rawToken,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", "user-uuid-123", "email", "alex@example.com")
        );
        Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"));

        when(jwtDecoder.decode(rawToken)).thenReturn(jwt);
        when(jwtRoleConverter.convert(jwt)).thenReturn(authorities);

        Message<?> result = interceptor.preSend(message, messageChannel);

        assertNotNull(result);
        StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
        assertNotNull(resultAccessor.getUser());
        assertInstanceOf(JwtAuthenticationToken.class, resultAccessor.getUser());
        JwtAuthenticationToken principal = (JwtAuthenticationToken) resultAccessor.getUser();
        assertEquals(jwt, principal.getToken());
        assertEquals(authorities, principal.getAuthorities());
        verify(jwtDecoder).decode(rawToken);
        verify(jwtRoleConverter).convert(jwt);
    }

    @Test
    @DisplayName("Should authenticate user when lowercase authorization header is provided")
    void preSend_ValidBearerToken_LowercaseHeader_AuthenticatesUser() {
        String rawToken = "valid.jwt.token";
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        accessor.addNativeHeader("authorization", "Bearer " + rawToken);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Jwt jwt = new Jwt(
                rawToken,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", "user-uuid-123", "email", "alex@example.com")
        );
        Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"));

        when(jwtDecoder.decode(rawToken)).thenReturn(jwt);
        when(jwtRoleConverter.convert(jwt)).thenReturn(authorities);

        Message<?> result = interceptor.preSend(message, messageChannel);

        assertNotNull(result);
        StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
        assertNotNull(resultAccessor.getUser());
        assertInstanceOf(JwtAuthenticationToken.class, resultAccessor.getUser());
    }

    @Test
    @DisplayName("Should allow anonymous connection with AnonymousAuthenticationToken when no Authorization header")
    void preSend_NoAuthorizationHeader_AllowsAnonymousConnection() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, messageChannel);

        assertNotNull(result);
        StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
        assertNotNull(resultAccessor.getUser());
        assertInstanceOf(AnonymousAuthenticationToken.class, resultAccessor.getUser());
        verifyNoInteractions(jwtDecoder, jwtRoleConverter);
    }

    @Test
    @DisplayName("Should throw BadCredentialsException when Authorization header does not start with Bearer")
    void preSend_MalformedHeader_ThrowsBadCredentialsException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        accessor.addNativeHeader(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        BadCredentialsException exception = assertThrows(
                BadCredentialsException.class,
                () -> interceptor.preSend(message, messageChannel)
        );
        assertTrue(exception.getMessage().contains("Must start with 'Bearer '"));
        verifyNoInteractions(jwtDecoder, jwtRoleConverter);
    }

    @Test
    @DisplayName("Should throw BadCredentialsException when JwtDecoder fails with BadJwtException")
    void preSend_ExpiredOrInvalidToken_ThrowsBadCredentialsException() {
        String rawToken = "expired.jwt.token";
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        accessor.addNativeHeader(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        when(jwtDecoder.decode(rawToken)).thenThrow(new BadJwtException("Token has expired"));

        BadCredentialsException exception = assertThrows(
                BadCredentialsException.class,
                () -> interceptor.preSend(message, messageChannel)
        );
        assertTrue(exception.getMessage().contains("Token has expired"));
        verify(jwtDecoder).decode(rawToken);
    }

    @Test
    @DisplayName("Should pass non-CONNECT STOMP frames through without re-decoding")
    void preSend_SubscribeFrame_PassesThrough() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setLeaveMutable(true);
        accessor.setDestination("/topic/events/event-123/seats");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, messageChannel);

        assertNotNull(result);
        verifyNoInteractions(jwtDecoder, jwtRoleConverter);
    }
}
