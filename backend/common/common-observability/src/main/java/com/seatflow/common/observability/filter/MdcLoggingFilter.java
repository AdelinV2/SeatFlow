package com.seatflow.common.observability.filter;

import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.common.observability.logging.StructuredLogFields;
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
 * Servlet filter that populates standard ECS/Logstash structured MDC fields
 * (correlation.id, user.id, http.method, http.uri, http.client_ip, trace.id, span.id)
 * and safely restores any pre-existing outer MDC context in {@code finally}.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class MdcLoggingFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    // Standardized field references
    public static final String MDC_CORRELATION_ID = StructuredLogFields.CORRELATION_ID;
    public static final String MDC_USER_ID = StructuredLogFields.USER_ID;
    public static final String MDC_HTTP_METHOD = StructuredLogFields.HTTP_METHOD;
    public static final String MDC_HTTP_URI = StructuredLogFields.HTTP_URI;
    public static final String MDC_CLIENT_IP = StructuredLogFields.HTTP_CLIENT_IP;
    public static final String MDC_TRACE_ID = StructuredLogFields.TRACE_ID;
    public static final String MDC_SPAN_ID = StructuredLogFields.SPAN_ID;

    @Value("${seatflow.observability.trust-forwarded-headers:false}")
    private boolean trustForwardedHeaders = false;

    private final ObjectProvider<Tracer> tracerProvider;

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

        String rawCorrelationId = request.getHeader(CORRELATION_ID_HEADER);
        String correlationId = isValidUuid(rawCorrelationId) ? rawCorrelationId.trim() : UUID.randomUUID().toString();

        CorrelationContext.setCorrelationId(correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        MDC.put(StructuredLogFields.CORRELATION_ID, correlationId);
        MDC.put(StructuredLogFields.HTTP_METHOD, request.getMethod());
        MDC.put(StructuredLogFields.HTTP_URI, request.getRequestURI());
        MDC.put(StructuredLogFields.HTTP_CLIENT_IP, resolveClientIp(request));

        // Inject authenticated user ID if available
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && StringUtils.hasText(auth.getName()) && !"anonymousUser".equalsIgnoreCase(auth.getName())) {
            MDC.put(StructuredLogFields.USER_ID, auth.getName());
        }

        // Inject trace and span IDs if Micrometer Tracer is active
        if (tracerProvider != null) {
            Tracer tracer = tracerProvider.getIfAvailable();
            if (tracer != null && tracer.currentSpan() != null && tracer.currentSpan().context() != null) {
                String traceId = tracer.currentSpan().context().traceId();
                String spanId = tracer.currentSpan().context().spanId();
                if (StringUtils.hasText(traceId)) {
                    MDC.put(StructuredLogFields.TRACE_ID, traceId);
                }
                if (StringUtils.hasText(spanId)) {
                    MDC.put(StructuredLogFields.SPAN_ID, spanId);
                }
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previousContext != null && !previousContext.isEmpty()) {
                MDC.setContextMap(previousContext);
            } else {
                MDC.clear();
            }
            CorrelationContext.clear();
        }
    }

    private boolean isValidUuid(String candidate) {
        if (!StringUtils.hasText(candidate)) {
            return false;
        }
        try {
            UUID.fromString(candidate.trim());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (trustForwardedHeaders) {
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(xForwardedFor)) {
                return xForwardedFor.split(",")[0].trim();
            }
        }
        String remoteAddr = request.getRemoteAddr();
        return StringUtils.hasText(remoteAddr) ? remoteAddr : "unknown";
    }
}
