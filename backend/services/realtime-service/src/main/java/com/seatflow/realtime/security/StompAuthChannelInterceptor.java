package com.seatflow.realtime.security;

import com.seatflow.common.security.converter.JwtRoleConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private final JwtDecoder jwtDecoder;
    private final JwtRoleConverter jwtRoleConverter;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && (StompCommand.CONNECT.equals(accessor.getCommand()) || StompCommand.STOMP.equals(accessor.getCommand()))) {
            String authHeader = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || authHeader.isBlank()) {
                authHeader = accessor.getFirstNativeHeader("authorization");
            }

            if (authHeader != null && !authHeader.isBlank()) {
                if (!authHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                    log.warn("STOMP CONNECT rejected: Malformed Authorization header format");
                    throw new BadCredentialsException("Malformed Authorization header: Must start with 'Bearer '");
                }

                String token = authHeader.substring(BEARER_PREFIX.length()).trim();
                if (token.isEmpty()) {
                    log.warn("STOMP CONNECT rejected: Bearer token is empty");
                    throw new BadCredentialsException("Bearer token must not be blank");
                }

                try {
                    Jwt jwt = jwtDecoder.decode(token);
                    var authorities = jwtRoleConverter.convert(jwt);
                    JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, authorities);
                    accessor.setUser(authentication);
                    log.info("STOMP CONNECT authenticated for user: sub={}, authorities={}",
                            jwt.getSubject(), authorities);
                } catch (JwtException | IllegalArgumentException ex) {
                    log.warn("STOMP CONNECT rejected: JWT validation failed - {}", ex.getMessage());
                    throw new BadCredentialsException("Invalid or expired JWT token: " + ex.getMessage(), ex);
                }
            } else {
                // Anonymous guest connection permitted per ADR-001 for live seat viewing
                String guestId = "guest-" + UUID.randomUUID();
                AnonymousAuthenticationToken anonymousAuth = new AnonymousAuthenticationToken(
                        "realtime-guest-key",
                        guestId,
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))
                );
                accessor.setUser(anonymousAuth);
                log.debug("STOMP CONNECT initialized with anonymous guest principal: {}", guestId);
            }
        }

        return message;
    }
}
