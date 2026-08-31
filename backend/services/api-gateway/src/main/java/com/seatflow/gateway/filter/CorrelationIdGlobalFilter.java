package com.seatflow.gateway.filter;

import com.seatflow.common.observability.logging.StructuredLogFields;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reactive request-context bridge for the WebFlux gateway. The shared servlet
 * filter cannot run here, so this filter stores the standard structured fields
 * in Reactor context and relies on Micrometer context propagation to scope MDC
 * values safely across scheduler changes.
 */
@org.springframework.stereotype.Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdGlobalFilter.class);
    private static final AtomicBoolean MDC_CONTEXT_PROPAGATION_CONFIGURED = new AtomicBoolean();

    private final ObjectProvider<Tracer> tracerProvider;

    @Value("${seatflow.observability.trust-forwarded-headers:false}")
    private boolean trustForwardedHeaders;

    public CorrelationIdGlobalFilter() {
        this.tracerProvider = null;
        configureMdcContextPropagation();
    }

    @Autowired
    public CorrelationIdGlobalFilter(ObjectProvider<Tracer> tracerProvider) {
        this.tracerProvider = tracerProvider;
        configureMdcContextPropagation();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String correlationId = resolveCorrelationId(request.getHeaders().getFirst(CORRELATION_ID_HEADER));
        ServerHttpRequest mutatedRequest = request.mutate()
                .header(CORRELATION_ID_HEADER, correlationId)
                .build();

        exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);
        Map<String, String> requestContext = requestContext(request, correlationId);

        return org.springframework.security.core.context.ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(this::isAuthenticatedUser)
                .map(Authentication::getName)
                .defaultIfEmpty("")
                .flatMap(userId -> {
                    Map<String, String> mdcContext = new HashMap<>(requestContext);
                    if (StringUtils.hasText(userId)) {
                        mdcContext.put(StructuredLogFields.USER_ID, userId);
                    }
                    return chain.filter(exchange.mutate().request(mutatedRequest).build())
                            .doOnSuccess(ignored -> logRateLimitRejection(exchange))
                            .contextWrite(context -> context.put(
                                    Slf4jThreadLocalAccessor.KEY,
                                    Map.copyOf(mdcContext)
                            ));
                });
    }

    private Map<String, String> requestContext(ServerHttpRequest request, String correlationId) {
        Map<String, String> context = new HashMap<>();
        context.put(StructuredLogFields.CORRELATION_ID, correlationId);
        context.put(StructuredLogFields.HTTP_METHOD, request.getMethod() == null ? "UNKNOWN" : request.getMethod().name());
        context.put(StructuredLogFields.HTTP_URI, request.getURI().getRawPath());
        context.put(StructuredLogFields.HTTP_CLIENT_IP, resolveClientIp(request));
        injectTraceContext(context);
        return context;
    }

    private void logRateLimitRejection(ServerWebExchange exchange) {
        if (HttpStatus.TOO_MANY_REQUESTS.equals(exchange.getResponse().getStatusCode())) {
            log.warn("Gateway rate-limit rejection.");
        }
    }

    private boolean isAuthenticatedUser(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && StringUtils.hasText(authentication.getName())
                && !"anonymousUser".equalsIgnoreCase(authentication.getName());
    }

    private void injectTraceContext(Map<String, String> context) {
        if (tracerProvider == null) {
            return;
        }

        Tracer tracer = tracerProvider.getIfAvailable();
        Span span = tracer == null ? null : tracer.currentSpan();
        TraceContext traceContext = span == null ? null : span.context();
        if (traceContext == null) {
            return;
        }

        if (isValidW3cHexId(traceContext.traceId(), 32)) {
            context.put(StructuredLogFields.TRACE_ID, traceContext.traceId());
        }
        if (isValidW3cHexId(traceContext.spanId(), 16)) {
            context.put(StructuredLogFields.SPAN_ID, traceContext.spanId());
        }
    }

    private String resolveCorrelationId(String candidate) {
        if (StringUtils.hasText(candidate)) {
            String trimmed = candidate.trim();
            try {
                UUID parsed = UUID.fromString(trimmed);
                if (parsed.toString().equalsIgnoreCase(trimmed)) {
                    return trimmed;
                }
            } catch (IllegalArgumentException ignored) {
                // A malformed client value must never become a request identifier.
            }
        }
        return UUID.randomUUID().toString();
    }

    private String resolveClientIp(ServerHttpRequest request) {
        if (trustForwardedHeaders) {
            String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
            if (StringUtils.hasText(forwardedFor)) {
                String forwardedClientIp = forwardedFor.split(",", 2)[0].trim();
                if (StringUtils.hasText(forwardedClientIp)) {
                    return forwardedClientIp;
                }
            }
        }

        InetSocketAddress remoteAddress = request.getRemoteAddress();
        return remoteAddress == null ? "unknown" : remoteAddress.getHostString();
    }

    private boolean isValidW3cHexId(String value, int expectedLength) {
        return StringUtils.hasText(value)
                && value.length() == expectedLength
                && !value.chars().allMatch(character -> character == '0')
                && value.chars().allMatch(character -> (character >= '0' && character <= '9')
                || (character >= 'a' && character <= 'f'));
    }

    private static void configureMdcContextPropagation() {
        if (!MDC_CONTEXT_PROPAGATION_CONFIGURED.compareAndSet(false, true)) {
            return;
        }

        ContextRegistry registry = ContextRegistry.getInstance();
        boolean accessorAlreadyRegistered = registry.getThreadLocalAccessors().stream()
                .anyMatch(accessor -> Slf4jThreadLocalAccessor.KEY.equals(accessor.key()));
        if (!accessorAlreadyRegistered) {
            registry.registerThreadLocalAccessor(new Slf4jThreadLocalAccessor(
                    StructuredLogFields.TRACE_ID,
                    StructuredLogFields.SPAN_ID,
                    StructuredLogFields.CORRELATION_ID,
                    StructuredLogFields.USER_ID,
                    StructuredLogFields.HTTP_METHOD,
                    StructuredLogFields.HTTP_URI,
                    StructuredLogFields.HTTP_CLIENT_IP
            ));
        }
        Hooks.enableAutomaticContextPropagation();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
