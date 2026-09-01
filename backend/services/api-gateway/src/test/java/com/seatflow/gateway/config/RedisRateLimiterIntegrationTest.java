package com.seatflow.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;
import static org.assertj.core.api.Assertions.assertThat;
import reactor.core.publisher.Mono;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "seatflow.rate-limit.replenish-rate=1",
        "seatflow.rate-limit.burst-capacity=2",
        "seatflow.rate-limit.requested-tokens=1"
})
@ActiveProfiles("test")
class RedisRateLimiterIntegrationTest {

    private static final String ROUTE_ID = "reservation-create-rate-limited";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        registry.add("spring.data.redis.ssl.enabled", () -> false);
    }

    @Autowired
    private RedisRateLimiter redisRateLimiter;

    @Autowired
    private org.springframework.cloud.gateway.route.RouteLocator routeLocator;

    @Test
    void returnsHttp429AndDoesNotInvokeDownstreamAfterTheConfiguredQuotaIsExhausted() {
        AtomicInteger downstreamInvocations = new AtomicInteger();
        Route route = routeLocator.getRoutes()
                .filter(candidate -> ROUTE_ID.equals(candidate.getId()))
                .next()
                .block();
        assertThat(route).isNotNull();
        GatewayFilter filter = route.getFilters().getFirst();

        apply(filter, route, downstreamInvocations);
        apply(filter, route, downstreamInvocations);
        MockServerWebExchange exhausted = apply(filter, route, downstreamInvocations);

        assertThat(exhausted.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(downstreamInvocations.get()).isEqualTo(2);
    }

    @Test
    void keepsRateLimitBucketsIndependentPerResolvedCallerKey() {
        // Warm the Lettuce connection so connection setup time cannot refill the one-second bucket.
        redisRateLimiter.isAllowed(ROUTE_ID, "warmup").block();
        assertThat(redisRateLimiter.isAllowed(ROUTE_ID, "user:alice").block().isAllowed()).isTrue();
        assertThat(redisRateLimiter.isAllowed(ROUTE_ID, "user:alice").block().isAllowed()).isTrue();
        assertThat(redisRateLimiter.isAllowed(ROUTE_ID, "user:alice").block().isAllowed()).isFalse();

        assertThat(redisRateLimiter.isAllowed(ROUTE_ID, "user:bob").block().isAllowed()).isTrue();
    }

    private MockServerWebExchange apply(GatewayFilter filter, Route route, AtomicInteger downstreamInvocations) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/reservations").build());
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
        filter.filter(exchange, ignored -> {
            downstreamInvocations.incrementAndGet();
            return Mono.empty();
        }).block();
        return exchange;
    }
}
