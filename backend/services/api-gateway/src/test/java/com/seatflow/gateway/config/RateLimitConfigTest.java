package com.seatflow.gateway.config;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RateLimitConfigTest {

    private final RateLimitConfig config = new RateLimitConfig();

    @Test
    void resolvesVerifiedJwtSubjectBeforeRemoteAddress() {
        Jwt jwt = new Jwt(
                "signed-token",
                Instant.now(),
                Instant.now().plusSeconds(60),
                Map.of("alg", "RS256"),
                Map.of("sub", "customer-42"));
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/reservations")
                        .remoteAddress(new InetSocketAddress("203.0.113.9", 12345))
                        .build())
                .mutate()
                .principal(Mono.just(new JwtAuthenticationToken(jwt)))
                .build();

        assertThat(config.rateLimitKeyResolver().resolve(exchange).block()).isEqualTo("user:customer-42");
    }

    @Test
    void fallsBackToNormalizedRemoteAddressForAnonymousCallers() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/reservations")
                        .remoteAddress(new InetSocketAddress("2001:db8::7", 12345))
                        .build());

        assertThat(config.rateLimitKeyResolver().resolve(exchange).block()).isEqualTo("ip:2001:db8:0:0:0:0:0:7");
    }

    @Test
    void usesUnknownWhenTheTransportDoesNotExposeARemoteAddress() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/reservations").build());

        assertThat(config.rateLimitKeyResolver().resolve(exchange).block()).isEqualTo("ip:unknown");
    }

    @Test
    void usesForwardedClientOnlyWhenImmediatePeerIsTrusted() {
        MockServerWebExchange trusted = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/reservations")
                        .remoteAddress(new InetSocketAddress("10.0.0.4", 12345))
                        .header("X-Forwarded-For", "198.51.100.9, 10.0.0.3")
                        .build());
        RateLimitProperties properties = new RateLimitProperties(20, 40, 1, List.of("10.0.0.0/24"));

        assertThat(RateLimitConfig.normalizedRemoteAddress(trusted, properties)).isEqualTo("198.51.100.9");

        MockServerWebExchange untrusted = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/reservations")
                        .remoteAddress(new InetSocketAddress("203.0.113.7", 12345))
                        .header("X-Forwarded-For", "198.51.100.9")
                        .build());
        assertThat(RateLimitConfig.normalizedRemoteAddress(untrusted, properties)).isEqualTo("203.0.113.7");

        MockServerWebExchange trustedOnlyChain = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/reservations")
                        .remoteAddress(new InetSocketAddress("10.0.0.4", 12345))
                        .header("X-Forwarded-For", "10.0.0.2, 10.0.0.3")
                        .build());
        assertThat(RateLimitConfig.normalizedRemoteAddress(trustedOnlyChain, properties)).isEqualTo("10.0.0.4");
    }

    @Test
    void rejectsARequestCostLargerThanTheBucket() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateLimitProperties(1, 2, 3, List.of()))
                .withMessageContaining("requestedTokens");
    }
}
