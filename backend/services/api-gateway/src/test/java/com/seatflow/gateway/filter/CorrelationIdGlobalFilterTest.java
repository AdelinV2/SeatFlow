package com.seatflow.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdGlobalFilterTest {

    private final CorrelationIdGlobalFilter filter = new CorrelationIdGlobalFilter();

    @Test
    @DisplayName("Should generate X-Correlation-Id when missing from request")
    void shouldGenerateCorrelationIdWhenMissing() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/events").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> downstreamHeader = new AtomicReference<>();
        GatewayFilterChain chain = mutatedExchange -> {
            downstreamHeader.set(mutatedExchange.getRequest().getHeaders().getFirst("X-Correlation-Id"));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstreamHeader.get()).isNotBlank();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Correlation-Id"))
                .isEqualTo(downstreamHeader.get());
    }

    @Test
    @DisplayName("Should preserve existing valid UUID X-Correlation-Id from request")
    void shouldPreserveExistingCorrelationId() {
        String existingId = UUID.randomUUID().toString();
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/events")
                .header("X-Correlation-Id", existingId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> downstreamHeader = new AtomicReference<>();
        GatewayFilterChain chain = mutatedExchange -> {
            downstreamHeader.set(mutatedExchange.getRequest().getHeaders().getFirst("X-Correlation-Id"));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstreamHeader.get()).isEqualTo(existingId);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Correlation-Id")).isEqualTo(existingId);
    }

    @Test
    @DisplayName("Should replace invalid X-Correlation-Id with a fresh UUID")
    void shouldReplaceInvalidCorrelationIdWithFreshUuid() {
        String invalidId = "not-a-valid-uuid";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/events")
                .header("X-Correlation-Id", invalidId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> downstreamHeader = new AtomicReference<>();
        GatewayFilterChain chain = mutatedExchange -> {
            downstreamHeader.set(mutatedExchange.getRequest().getHeaders().getFirst("X-Correlation-Id"));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstreamHeader.get()).isNotEqualTo(invalidId);
        assertThat(UUID.fromString(downstreamHeader.get())).isNotNull();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Correlation-Id"))
                .isEqualTo(downstreamHeader.get());
    }

    @Test
    @DisplayName("Should have highest precedence order")
    void shouldHaveHighestPrecedence() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
