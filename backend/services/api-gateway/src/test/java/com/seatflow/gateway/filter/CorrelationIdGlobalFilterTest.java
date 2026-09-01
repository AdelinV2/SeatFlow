package com.seatflow.gateway.filter;

import com.seatflow.common.observability.logging.StructuredLogFields;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdGlobalFilterTest {

    private final CorrelationIdGlobalFilter filter = new CorrelationIdGlobalFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldGenerateCorrelationIdWhenMissing() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/events").build());
        AtomicReference<String> downstreamHeader = new AtomicReference<>();

        filter.filter(exchange, captureHeaderChain(downstreamHeader)).block();

        assertThat(downstreamHeader.get()).isNotBlank();
        assertThat(UUID.fromString(downstreamHeader.get())).isNotNull();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Correlation-Id"))
                .isEqualTo(downstreamHeader.get());
    }

    @Test
    void shouldPreserveExistingCanonicalCorrelationIdAndReplaceInvalidValues() {
        String existingId = UUID.randomUUID().toString();
        MockServerWebExchange validExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/events")
                .header("X-Correlation-Id", existingId).build());
        AtomicReference<String> validHeader = new AtomicReference<>();

        filter.filter(validExchange, captureHeaderChain(validHeader)).block();
        assertThat(validHeader.get()).isEqualTo(existingId);

        MockServerWebExchange invalidExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/events")
                .header("X-Correlation-Id", "1-1-1-1-1").build());
        AtomicReference<String> invalidHeader = new AtomicReference<>();
        filter.filter(invalidExchange, captureHeaderChain(invalidHeader)).block();

        assertThat(invalidHeader.get()).isNotEqualTo("1-1-1-1-1");
        assertThat(UUID.fromString(invalidHeader.get())).isNotNull();
    }

    @Test
    void shouldPropagateStructuredMdcAcrossSchedulerHopAndRestoreItAfterCompletion() {
        String correlationId = UUID.randomUUID().toString();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/reservations")
                .header("X-Correlation-Id", correlationId).build());
        AtomicReference<Map<String, String>> captured = new AtomicReference<>();
        GatewayFilterChain chain = ignoredExchange -> Mono.just("continue")
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(ignored -> captured.set(new HashMap<>(MDC.getCopyOfContextMap())))
                .then();

        filter.filter(exchange, chain).block();

        assertThat(captured.get()).containsEntry(StructuredLogFields.CORRELATION_ID, correlationId)
                .containsEntry(StructuredLogFields.HTTP_METHOD, "POST")
                .containsEntry(StructuredLogFields.HTTP_URI, "/api/reservations");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void shouldHaveHighestPrecedenceOrder() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    private GatewayFilterChain captureHeaderChain(AtomicReference<String> downstreamHeader) {
        return exchange -> {
            downstreamHeader.set(exchange.getRequest().getHeaders().getFirst("X-Correlation-Id"));
            return Mono.empty();
        };
    }
}
