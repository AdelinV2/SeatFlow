# TASK-P07-002: STOMP Security & Inbound Channel Authentication Interceptor

## 1. Task Metadata
- **Task ID:** `TASK-P07-002`
- **Git Branch:** `feat/p07-002-stomp-security-and-channel-interceptor`
- **Target Module:** `backend/services/realtime-service`
- **Phase:** `Phase 07 - Realtime WebSocket Service`
- **Related Specs:** `.ai/architecture/01-common-modules.md` (Section 4: `common-security`), `.ai/architecture/02-microservices-spec.md` (Section 9: Realtime Service), `.ai/architecture/04-authentication-security.md`, `.ai/architecture/08-observability-and-deployment.md`
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`
- **Status:** `COMPLETED`

---

## 2. Objective & Invariants
Establish transport and protocol security for `realtime-service`. Configure the HTTP `SecurityFilterChain` permitting public handshake and actuator endpoints while securing STOMP frame communication via an inbound `ChannelInterceptor` (`StompAuthChannelInterceptor`). The interceptor intercepts STOMP `CONNECT` frames, extracts Bearer JWT tokens from native headers, decodes and validates them via `JwtDecoder`, extracts roles using `JwtRoleConverter` from `common-security`, and binds the authenticated principal to the WebSocket session. In accordance with ADR-001 (Guest Checkout & Public Seat Views), unauthenticated guest connections are permitted as anonymous principals so guests can observe real-time seat availability without logging in.

### Critical Invariants to Enforce:
- [x] **Stateless HTTP Filter Chain:** Disable CSRF, set session creation policy to `STATELESS`, and permit all `/ws/**` handshake endpoints and `/actuator/health`, `/actuator/info`, `/actuator/prometheus`.
- [x] **Frame-Level STOMP Authentication:** Validate authentication inside `StompAuthChannelInterceptor` on `StompCommand.CONNECT` frames via native headers rather than relying on HTTP cookie/session handshakes.
- [x] **ADR-001 Guest Support (Anonymous Mode):** If the `Authorization` header is absent during `CONNECT`, permit the connection under an `AnonymousAuthenticationToken` (or guest principal) to enable real-time public seat map viewing.
- [x] **Strict Invalid Token Rejection:** If an `Authorization` header is present but malformed, expired, or cryptographically invalid (fails `JwtDecoder.decode()`), immediately throw `BadCredentialsException` / `AuthenticationCredentialsNotFoundException` to terminate the connection.
- [x] **Common-Security Integration:** Use `JwtRoleConverter` from `common-security` to map Entra ID roles/claims to Spring Security `GrantedAuthority` instances on the resulting `JwtAuthenticationToken`.
- [x] **Zero `@RestControllerAdvice` Rule:** Do NOT add `@RestControllerAdvice` in this service; all HTTP error mapping is inherited from `common-observability`.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/realtime-service/src/main/java/com/seatflow/realtime/config/SecurityConfig.java`
- `[NEW]` `backend/services/realtime-service/src/main/java/com/seatflow/realtime/security/StompAuthChannelInterceptor.java`
- `[MODIFY]` `backend/services/realtime-service/src/main/java/com/seatflow/realtime/config/WebSocketConfig.java` — inject `StompAuthChannelInterceptor` and register in `configureClientInboundChannel(ChannelRegistration registration)`.
- `[NEW]` `backend/services/realtime-service/src/test/java/com/seatflow/realtime/security/StompAuthChannelInterceptorTest.java`
- `[NEW]` `backend/services/realtime-service/src/test/java/com/seatflow/realtime/config/SecurityConfigTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Spring HTTP Security Configuration (`SecurityConfig.java`)

```java
package com.seatflow.realtime.config;

import com.seatflow.common.security.converter.JwtRoleConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtRoleConverter jwtRoleConverter;

    @Value("${seatflow.cors.allowed-origins:http://localhost:4200}")
    private String allowedOrigins;

    /**
     * Wraps the granted-authorities converter (JwtRoleConverter) into a full
     * JwtAuthenticationConverter as required by Spring Security's resource server.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(JwtRoleConverter jwtRoleConverter) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwtRoleConverter);
        return converter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public WebSocket handshake endpoints
                        .requestMatchers("/ws/**").permitAll()
                        // Actuator health, info, prometheus endpoints
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        // Swagger/OpenAPI docs
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Any other administrative/internal endpoints require authentication
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                )
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        configuration.setAllowedOriginPatterns(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
```

---

### 4.2 STOMP Inbound Channel Authentication Interceptor (`StompAuthChannelInterceptor.java`)

```java
package com.seatflow.realtime.security;

import com.seatflow.common.security.converter.JwtRoleConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private final JwtDecoder jwtDecoder;
    private final JwtRoleConverter jwtRoleConverter;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);

            if (authHeader != null && !authHeader.isBlank()) {
                if (!authHeader.startsWith(BEARER_PREFIX)) {
                    log.warn("STOMP CONNECT rejected: Malformed Authorization header format");
                    throw new BadCredentialsException("Malformed Authorization header: Must start with 'Bearer '");
                }

                String token = authHeader.substring(BEARER_PREFIX.length()).trim();
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
```

---

### 4.3 Modified `WebSocketConfig.java`
Update `WebSocketConfig` to inject `StompAuthChannelInterceptor` and register it on client inbound channels:

```java
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
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        log.info("Registering STOMP endpoints on /ws with allowed origins: {}", Arrays.toString(origins));

        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins)
                .withSockJS();

        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Register the authentication interceptor on the inbound client message channel
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
```

---

### 4.4 Unit Tests

#### `src/test/java/com/seatflow/realtime/security/StompAuthChannelInterceptorTest.java`:
```java
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
```

#### `src/test/java/com/seatflow/realtime/config/SecurityConfigTest.java`:
```java
package com.seatflow.realtime.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("Should permit unauthenticated access to /ws endpoint handshake")
    void wsHandshakeEndpoint_PermittedWithoutAuth() throws Exception {
        mockMvc.perform(get("/ws/info"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should permit unauthenticated access to actuator health")
    void actuatorHealth_PermittedWithoutAuth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Security Configuration:** Create `com.seatflow.realtime.config.SecurityConfig` defining `SecurityFilterChain` permitting `/ws/**`, actuator endpoints, and configuring CORS and OAuth2 Resource Server.
2. **Channel Interceptor:** Create `com.seatflow.realtime.security.StompAuthChannelInterceptor` implementing `ChannelInterceptor`:
   - Inspect `StompCommand.CONNECT`.
   - Extract `Authorization` native header.
   - If present: validate `Bearer ` prefix, decode JWT via `JwtDecoder`, convert via `JwtRoleConverter`, set `JwtAuthenticationToken` on `accessor.setUser(...)`. Throw `BadCredentialsException` on failure.
   - If absent: assign `AnonymousAuthenticationToken`.
3. **WebSocket Configuration Update:** Modify `com.seatflow.realtime.config.WebSocketConfig` to inject `StompAuthChannelInterceptor` and register it in `configureClientInboundChannel(...)`.
4. **Testing & Verification:**
   - Implement `StompAuthChannelInterceptorTest` covering valid token, missing token, malformed header, and expired token cases.
   - Implement `SecurityConfigTest` verifying public path permits.
   - Run Maven test command to verify all tests pass.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
mvn clean test -pl backend/services/realtime-service -Dtest=StompAuthChannelInterceptorTest,SecurityConfigTest
```
- [x] `SecurityConfig` permits `/ws/**` and actuator endpoints while configuring stateless OAuth2 resource server security.
- [x] `StompAuthChannelInterceptor` successfully authenticates valid JWT tokens and assigns `JwtAuthenticationToken` on `CONNECT`.
- [x] `StompAuthChannelInterceptor` allows anonymous guest connections with `AnonymousAuthenticationToken` per ADR-001.
- [x] `StompAuthChannelInterceptor` rejects malformed and expired tokens with `BadCredentialsException`.
- [x] `WebSocketConfig` registers `StompAuthChannelInterceptor` on the client inbound channel.
- [x] All unit tests pass cleanly.
- [x] Task file is moved to `.ai/tasks/completed/phase-07-realtime-service/002-stomp-security-and-channel-authentication-interceptor.md`.
