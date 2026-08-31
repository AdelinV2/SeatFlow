package com.seatflow.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final Logger log = LoggerFactory.getLogger(CorrelationIdGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String rawCorrelationId = request.getHeaders().getFirst(CORRELATION_ID_HEADER);
        String correlationId = isValidUuid(rawCorrelationId)
                ? rawCorrelationId.trim()
                : UUID.randomUUID().toString();

        final String finalCorrelationId = correlationId;

        ServerHttpRequest mutatedRequest = request.mutate()
                .header(CORRELATION_ID_HEADER, finalCorrelationId)
                .build();

        exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, finalCorrelationId);

        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .doOnSuccess(ignored -> logRateLimitRejection(request, finalCorrelationId, exchange));
    }

    private void logRateLimitRejection(ServerHttpRequest request, String correlationId,
                                       ServerWebExchange exchange) {
        if (HttpStatus.TOO_MANY_REQUESTS.equals(exchange.getResponse().getStatusCode())) {
            log.warn("Gateway rate-limit rejection. correlationId={}, httpMethod={}, httpUri={}",
                    correlationId, request.getMethod(), request.getURI().getPath());
        }
    }

    private boolean isValidUuid(String candidate) {
        if (!StringUtils.hasText(candidate)) {
            return false;
        }
        try {
            UUID parsed = UUID.fromString(candidate.trim());
            return parsed.toString().equalsIgnoreCase(candidate.trim());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
