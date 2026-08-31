package com.seatflow.common.observability.filter;

import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.common.observability.logging.StructuredLogFields;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Populates the standard dotted request fields used by production JSON logs.
 * The filter runs after Spring Security so an authenticated principal is available,
 * and restores the caller's MDC context when the request completes.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class MdcLoggingFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    public static final String MDC_CORRELATION_ID = StructuredLogFields.CORRELATION_ID;
    public static final String MDC_HTTP_METHOD = StructuredLogFields.HTTP_METHOD;
    public static final String MDC_HTTP_URI = StructuredLogFields.HTTP_URI;
    public static final String MDC_CLIENT_IP = StructuredLogFields.HTTP_CLIENT_IP;
    public static final String MDC_USER_ID = StructuredLogFields.USER_ID;
    public static final String MDC_TRACE_ID = StructuredLogFields.TRACE_ID;
    public static final String MDC_SPAN_ID = StructuredLogFields.SPAN_ID;

    /**
     * Forwarded headers are only considered when the deployment explicitly opts in.
     */
    @Value("${seatflow.observability.trust-forwarded-headers:false}")
    private boolean trustForwardedHeaders;

    private final ObjectProvider<Tracer> tracerProvider;

    /**
     * Keeps direct construction compatible with applications/tests that do not configure tracing.
     */
    public MdcLoggingFilter() {
        this.tracerProvider = null;
    }

    @Autowired
    public MdcLoggingFilter(ObjectProvider<Tracer> tracerProvider) {
        this.tracerProvider = tracerProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        try {
            String correlationId = resolveCorrelationId(request.getHeader(CORRELATION_ID_HEADER));
            CorrelationContext.setCorrelationId(correlationId);
            response.setHeader(CORRELATION_ID_HEADER, correlationId);

            MDC.put(StructuredLogFields.CORRELATION_ID, correlationId);
            MDC.put(StructuredLogFields.HTTP_METHOD, request.getMethod());
            MDC.put(StructuredLogFields.HTTP_URI, request.getRequestURI());
            MDC.put(StructuredLogFields.HTTP_CLIENT_IP, resolveClientIp(request));

            // These values belong to the current request. Remove any outer
            // values first; restoreMdcContext() puts them back after the chain.
            MDC.remove(StructuredLogFields.USER_ID);
            MDC.remove(StructuredLogFields.TRACE_ID);
            MDC.remove(StructuredLogFields.SPAN_ID);

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null
                    && authentication.isAuthenticated()
                    && StringUtils.hasText(authentication.getName())
                    && !"anonymousUser".equalsIgnoreCase(authentication.getName())) {
                MDC.put(StructuredLogFields.USER_ID, authentication.getName());
            }

            injectTraceContext();
            filterChain.doFilter(request, response);
        } finally {
            restoreMdcContext(previousContext);
            CorrelationContext.clear();
        }
    }

    private String resolveCorrelationId(String candidate) {
        if (StringUtils.hasText(candidate)) {
            String trimmed = candidate.trim();
            try {
                UUID parsed = UUID.fromString(trimmed);
                // UUID.fromString accepts shortened groups in some JDKs. Requiring
                // its canonical rendering prevents accepting ambiguous identifiers.
                if (parsed.toString().equalsIgnoreCase(trimmed)) {
                    return trimmed;
                }
            } catch (IllegalArgumentException ignored) {
                // Generate a fresh ID below for malformed/untrusted input.
            }
        }
        return UUID.randomUUID().toString();
    }

    private void injectTraceContext() {
        if (tracerProvider == null) {
            return;
        }

        Tracer tracer = tracerProvider.getIfAvailable();
        if (tracer == null) {
            return;
        }

        Span span = tracer.currentSpan();
        TraceContext context = span == null ? null : span.context();
        if (context == null) {
            return;
        }

        if (StringUtils.hasText(context.traceId())) {
            MDC.put(StructuredLogFields.TRACE_ID, context.traceId());
        }
        if (StringUtils.hasText(context.spanId())) {
            MDC.put(StructuredLogFields.SPAN_ID, context.spanId());
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (trustForwardedHeaders) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(forwardedFor)) {
                String forwardedClientIp = forwardedFor.split(",", 2)[0].trim();
                if (StringUtils.hasText(forwardedClientIp)) {
                    return forwardedClientIp;
                }
            }
        }

        String remoteAddress = request.getRemoteAddr();
        return StringUtils.hasText(remoteAddress) ? remoteAddress : "unknown";
    }

    private void restoreMdcContext(Map<String, String> previousContext) {
        if (previousContext == null || previousContext.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousContext);
        }
    }
}
